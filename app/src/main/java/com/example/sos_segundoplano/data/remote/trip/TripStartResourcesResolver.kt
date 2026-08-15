package com.example.sos_segundoplano.data.remote.trip

import com.example.sos_segundoplano.domain.auth.AuthFailure
import com.example.sos_segundoplano.domain.auth.AuthResult
import com.example.sos_segundoplano.domain.auth.InvalidResponse
import com.example.sos_segundoplano.domain.auth.NetworkUnavailable
import com.example.sos_segundoplano.domain.auth.Timeout
import com.example.sos_segundoplano.domain.repository.AuthRepository
import com.example.sos_segundoplano.data.remote.incident.ManualSosLocationProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class TripStartResources(
    val vehicleId: String,
    val mobileDeviceId: String,
    val smartwatchDeviceId: String?
)

sealed interface TripStartResourcesResult {
    data class Success(val resources: TripStartResources) : TripStartResourcesResult
    data class Failure(val result: TripMutationResult) : TripStartResourcesResult
}

fun interface TripStartResourcesResolver {
    suspend fun resolveTripStartResources(): TripStartResourcesResult
}

fun interface ResolvedRemoteTripStarter {
    suspend fun startTrip(): TripMutationResult

    suspend fun startTripWithoutInitialLocation(): TripMutationResult = startTrip()
}

enum class TripStartLocationCaptureState { Idle, Capturing, Available, Unavailable }

class AuthenticatedTripStartResourcesResolver(
    private val authRepository: AuthRepository,
    private val remoteDataSource: TripRemoteDataSource
) : TripStartResourcesResolver {
    private val mutex = Mutex()

    override suspend fun resolveTripStartResources(): TripStartResourcesResult = mutex.withLock {
        val vehicles = when (val result = authorizedLookup(remoteDataSource::vehicles)) {
            is TripStartResourceLookupResult.Success -> result.value
            else -> return@withLock TripStartResourcesResult.Failure(result.toTripMutationResult())
        }
        val vehicle = when (val selection = TripStartResourceSelector.selectVehicle(vehicles)) {
            is ResourceSelection.Selected -> selection.value
            is ResourceSelection.Unavailable -> return@withLock TripStartResourcesResult.Failure(
                TripMutationResult.MissingRequiredData(selection.reason)
            )
        }

        val devices = when (val result = authorizedLookup(remoteDataSource::devices)) {
            is TripStartResourceLookupResult.Success -> result.value
            else -> return@withLock TripStartResourcesResult.Failure(result.toTripMutationResult())
        }
        val mobile = when (val selection = TripStartResourceSelector.selectMobileDevice(devices)) {
            is ResourceSelection.Selected -> selection.value
            is ResourceSelection.Unavailable -> return@withLock TripStartResourcesResult.Failure(
                TripMutationResult.MissingRequiredData(selection.reason)
            )
        }
        val smartwatchId = TripStartResourceSelector.selectSmartwatch(devices, requireNotNull(mobile.id))
            ?.id
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        TripStartResourcesResult.Success(
            TripStartResources(
                vehicleId = requireNotNull(vehicle.id).trim(),
                mobileDeviceId = requireNotNull(mobile.id).trim(),
                smartwatchDeviceId = smartwatchId
            )
        )
    }

    private suspend fun <T> authorizedLookup(
        lookup: suspend (String) -> TripStartResourceLookupResult<T>
    ): TripStartResourceLookupResult<T> {
        val token = when (val result = authRepository.ensureValidAccessToken()) {
            is AuthResult.Success -> result.value.reveal()
            is AuthFailure -> return result.toResourceLookupResult()
        }
        val first = lookup("Bearer $token")
        if (first !is TripStartResourceLookupResult.HttpError || first.statusCode != 401) return first
        if (authRepository.refreshSession() !is AuthResult.Success) return first
        val refreshedToken = when (val result = authRepository.ensureValidAccessToken()) {
            is AuthResult.Success -> result.value.reveal()
            is AuthFailure -> return result.toResourceLookupResult()
        }
        return lookup("Bearer $refreshedToken")
    }
}

class DefaultResolvedRemoteTripStarter(
    private val resourcesResolver: TripStartResourcesResolver,
    private val remoteTripStarter: RemoteTripStarter,
    private val locationProvider: ManualSosLocationProvider? = null,
    private val onLocationCaptureStateChanged: (TripStartLocationCaptureState) -> Unit = {}
) : ResolvedRemoteTripStarter {
    override suspend fun startTrip(): TripMutationResult = startTrip(allowMissingStartLocation = false)

    override suspend fun startTripWithoutInitialLocation(): TripMutationResult =
        startTrip(allowMissingStartLocation = true)

    private suspend fun startTrip(allowMissingStartLocation: Boolean): TripMutationResult {
        return when (val result = resourcesResolver.resolveTripStartResources()) {
            is TripStartResourcesResult.Success -> {
                onLocationCaptureStateChanged(TripStartLocationCaptureState.Capturing)
                val location = locationProvider?.currentRealLocation()?.toTripLocationDto()
                onLocationCaptureStateChanged(
                    if (location == null) TripStartLocationCaptureState.Unavailable
                    else TripStartLocationCaptureState.Available
                )
                if (location == null && !allowMissingStartLocation) {
                    return TripMutationResult.MissingRequiredData("start_location_unavailable")
                }
                remoteTripStarter.startTrip(
                    StartTripRequestDto(
                        vehicleId = result.resources.vehicleId,
                        mobileDeviceId = result.resources.mobileDeviceId,
                        smartwatchDeviceId = result.resources.smartwatchDeviceId,
                        clientStartedAtUtc = java.time.Instant.now().toString(),
                        startLocation = location
                    )
                )
            }
            is TripStartResourcesResult.Failure -> result.result
        }
    }
}

internal sealed interface ResourceSelection<out T> {
    data class Selected<T>(val value: T) : ResourceSelection<T>
    data class Unavailable(val reason: String) : ResourceSelection<Nothing>
}

internal object TripStartResourceSelector {
    fun selectVehicle(vehicles: List<VehicleResourceDto>): ResourceSelection<VehicleResourceDto> {
        val eligible = vehicles.filter { vehicle ->
            vehicle.isActive == true &&
                vehicle.completionStatus.equals("Completed", ignoreCase = true) &&
                !vehicle.id.isNullOrBlank()
        }
        return selectRequired(
            eligible,
            "vehicle_not_available",
            "vehicle_selection_ambiguous",
            isPrimary = { it.isPrimary == true }
        )
    }

    fun selectMobileDevice(devices: List<DeviceResourceDto>): ResourceSelection<DeviceResourceDto> {
        val eligible = devices.filter { device ->
            device.isActive == true &&
                device.deviceType.equals("MobileApp", ignoreCase = true) &&
                device.linkStatus.equals("Linked", ignoreCase = true) &&
                !device.id.isNullOrBlank()
        }
        return selectRequired(
            eligible,
            "mobile_device_not_available",
            "mobile_device_selection_ambiguous",
            isPrimary = { it.isPrimary == true }
        )
    }

    fun selectSmartwatch(
        devices: List<DeviceResourceDto>,
        selectedMobileDeviceId: String
    ): DeviceResourceDto? {
        val eligible = devices.filter { device ->
            device.isActive == true &&
                device.deviceType.equals("Smartwatch", ignoreCase = true) &&
                device.linkStatus.equals("Linked", ignoreCase = true) &&
                device.parentDeviceId == selectedMobileDeviceId &&
                !device.id.isNullOrBlank()
        }
        return when (
            val selection = selectRequired(
                eligible,
                "smartwatch_not_available",
                "smartwatch_selection_ambiguous",
                isPrimary = { it.isPrimary == true }
            )
        ) {
            is ResourceSelection.Selected -> selection.value
            is ResourceSelection.Unavailable -> null
        }
    }

    private fun <T> selectRequired(
        eligible: List<T>,
        unavailableReason: String,
        ambiguousReason: String,
        isPrimary: (T) -> Boolean
    ): ResourceSelection<T> {
        if (eligible.isEmpty()) return ResourceSelection.Unavailable(unavailableReason)
        val primary = eligible.filter(isPrimary)
        if (primary.size == 1) return ResourceSelection.Selected(primary.single())
        if (primary.size > 1) return ResourceSelection.Unavailable(ambiguousReason)
        if (eligible.size == 1) return ResourceSelection.Selected(eligible.single())
        return ResourceSelection.Unavailable(ambiguousReason)
    }
}

private fun AuthFailure.toResourceLookupResult(): TripStartResourceLookupResult<Nothing> = when (this) {
    is NetworkUnavailable -> TripStartResourceLookupResult.NetworkUnavailable(sanitizedMessage ?: "network_unavailable")
    is Timeout -> TripStartResourceLookupResult.Timeout(sanitizedMessage ?: "network_timeout")
    is InvalidResponse -> TripStartResourceLookupResult.InvalidResponse(sanitizedMessage ?: "access_token_invalid")
    else -> TripStartResourceLookupResult.HttpError(401, "access_token_unavailable")
}

private fun TripStartResourceLookupResult<*>.toTripMutationResult(): TripMutationResult = when (this) {
    is TripStartResourceLookupResult.HttpError -> TripMutationResult.HttpError(statusCode, sanitizedMessage)
    is TripStartResourceLookupResult.NetworkUnavailable -> TripMutationResult.NetworkUnavailable(sanitizedMessage)
    is TripStartResourceLookupResult.Timeout -> TripMutationResult.Timeout(sanitizedMessage)
    is TripStartResourceLookupResult.InvalidResponse -> TripMutationResult.InvalidResponse(sanitizedMessage)
    is TripStartResourceLookupResult.Success -> TripMutationResult.InvalidResponse("resource_lookup_state_invalid")
}
