package com.example.sos_segundoplano.data.remote.trip

import com.example.sos_segundoplano.domain.auth.AccessToken
import com.example.sos_segundoplano.domain.auth.AuthResult
import com.example.sos_segundoplano.domain.auth.AuthUser
import com.example.sos_segundoplano.domain.auth.SessionState
import com.example.sos_segundoplano.domain.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripStartResourcesResolverTest {
    @Test fun vehicleSelectionUsesUniquePrimaryAndRejectsIneligibleOrAmbiguousCandidates() {
        val primary = vehicle("vehicle-primary", primary = true)
        val secondary = vehicle("vehicle-secondary")
        assertEquals(primary, selectedVehicle(listOf(secondary, primary)))
        assertEquals(
            secondary,
            selectedVehicle(
                listOf(
                    vehicle("inactive", active = false, primary = true),
                    vehicle("incomplete", completion = "InProgress", primary = true),
                    secondary
                )
            )
        )
        assertEquals(
            "vehicle_selection_ambiguous",
            unavailableReason(TripStartResourceSelector.selectVehicle(listOf(secondary, vehicle("vehicle-third"))))
        )
        assertEquals(
            "vehicle_not_available",
            unavailableReason(TripStartResourceSelector.selectVehicle(listOf(vehicle("inactive", active = false))))
        )
    }

    @Test fun mobileSelectionUsesOnlyLinkedActiveMobileAppAndRejectsAmbiguity() {
        val mobile = device("mobile-primary", "MobileApp", primary = true)
        assertEquals(
            mobile,
            selectedDevice(
                TripStartResourceSelector.selectMobileDevice(
                    listOf(
                        device("watch", "Smartwatch", primary = true),
                        device("revoked", "MobileApp", linkStatus = "Revoked", primary = true),
                        device("inactive", "MobileApp", active = false, primary = true),
                        mobile
                    )
                )
            )
        )
        val onlyEligible = device("mobile-only", "MobileApp")
        assertEquals(onlyEligible, selectedDevice(TripStartResourceSelector.selectMobileDevice(listOf(onlyEligible))))
        assertEquals(
            "mobile_device_selection_ambiguous",
            unavailableReason(
                TripStartResourceSelector.selectMobileDevice(
                    listOf(device("mobile-a", "MobileApp"), device("mobile-b", "MobileApp"))
                )
            )
        )
    }

    @Test fun smartwatchMustBeLinkedActiveAndParentedToSelectedMobileOtherwiseItIsNull() {
        val linkedWatch = device(
            id = "watch-linked",
            type = "Smartwatch",
            parentDeviceId = "mobile-selected"
        )
        assertEquals(
            linkedWatch,
            TripStartResourceSelector.selectSmartwatch(listOf(linkedWatch), "mobile-selected")
        )
        assertNull(
            TripStartResourceSelector.selectSmartwatch(
                listOf(device("watch-other", "Smartwatch", parentDeviceId = "other-mobile")),
                "mobile-selected"
            )
        )
        assertNull(TripStartResourceSelector.selectSmartwatch(emptyList(), "mobile-selected"))
    }

    @Test fun resolverGetsResourcesBeforeStartAndUsesOnlyBackendReturnedIds() = runBlocking {
        val order = mutableListOf<String>()
        val dataSource = FakeResourceDataSource(
            vehiclesResult = TripStartResourceLookupResult.Success(listOf(vehicle("vehicle-backend", primary = true))),
            devicesResult = TripStartResourceLookupResult.Success(
                listOf(device("mobile-backend", "MobileApp", primary = true))
            ),
            order = order
        )
        var request: StartTripRequestDto? = null
        val starter = DefaultResolvedRemoteTripStarter(
            AuthenticatedTripStartResourcesResolver(FakeAuthRepository(), dataSource),
            RemoteTripStarter {
                order += "start"
                request = it
                TripMutationResult.Success("remote-trip-canonical", "Active")
            }
        )

        val result = starter.startTrip()

        assertEquals(listOf("vehicles", "devices", "start"), order)
        assertEquals("vehicle-backend", request?.vehicleId)
        assertEquals("mobile-backend", request?.mobileDeviceId)
        assertNull(request?.smartwatchDeviceId)
        assertEquals(TripMutationResult.Success("remote-trip-canonical", "Active"), result)
    }

    @Test fun missingOrFailedResourcesNeverCallPostStart() = runBlocking {
        val cases = listOf(
            FakeResourceDataSource(
                TripStartResourceLookupResult.Success(emptyList()),
                TripStartResourceLookupResult.Success(emptyList())
            ) to TripMutationResult.MissingRequiredData("vehicle_not_available"),
            FakeResourceDataSource(
                TripStartResourceLookupResult.Success(listOf(vehicle("vehicle-backend", primary = true))),
                TripStartResourceLookupResult.NetworkUnavailable("network_unavailable")
            ) to TripMutationResult.NetworkUnavailable("network_unavailable")
        )
        cases.forEach { (dataSource, expected) ->
            var startCalls = 0
            val starter = DefaultResolvedRemoteTripStarter(
                AuthenticatedTripStartResourcesResolver(FakeAuthRepository(), dataSource),
                RemoteTripStarter {
                    startCalls++
                    TripMutationResult.Success("unexpected", "Active")
                }
            )

            assertEquals(expected, starter.startTrip())
            assertEquals(0, startCalls)
        }
    }

    private fun selectedVehicle(items: List<VehicleResourceDto>): VehicleResourceDto =
        when (val selection = TripStartResourceSelector.selectVehicle(items)) {
            is ResourceSelection.Selected -> selection.value
            is ResourceSelection.Unavailable -> error(selection.reason)
        }

    private fun selectedDevice(selection: ResourceSelection<DeviceResourceDto>): DeviceResourceDto =
        when (selection) {
            is ResourceSelection.Selected -> selection.value
            is ResourceSelection.Unavailable -> error(selection.reason)
        }

    private fun unavailableReason(selection: ResourceSelection<*>): String =
        when (selection) {
            is ResourceSelection.Selected -> error("expected unavailable")
            is ResourceSelection.Unavailable -> selection.reason
        }

    private fun vehicle(
        id: String,
        completion: String = "Completed",
        primary: Boolean = false,
        active: Boolean = true
    ) = VehicleResourceDto(id, completion, primary, active)

    private fun device(
        id: String,
        type: String,
        linkStatus: String = "Linked",
        parentDeviceId: String? = null,
        primary: Boolean = false,
        active: Boolean = true
    ) = DeviceResourceDto(id, type, linkStatus, parentDeviceId, primary, active)

    private class FakeResourceDataSource(
        private val vehiclesResult: TripStartResourceLookupResult<List<VehicleResourceDto>>,
        private val devicesResult: TripStartResourceLookupResult<List<DeviceResourceDto>>,
        private val order: MutableList<String> = mutableListOf()
    ) : TripRemoteDataSource {
        override suspend fun activeTrip(authorization: String): ActiveTripLookupResult =
            ActiveTripLookupResult.NoActiveTrip

        override suspend fun vehicles(
            authorization: String
        ): TripStartResourceLookupResult<List<VehicleResourceDto>> {
            order += "vehicles"
            return vehiclesResult
        }

        override suspend fun devices(
            authorization: String
        ): TripStartResourceLookupResult<List<DeviceResourceDto>> {
            order += "devices"
            return devicesResult
        }
    }

    private class FakeAuthRepository : AuthRepository {
        override suspend fun login(email: String, password: String, rememberMe: Boolean): AuthResult<AuthUser> = error("unused")
        override suspend fun restoreSession(): AuthResult<AuthUser?> = AuthResult.Success(null)
        override suspend fun ensureValidAccessToken(): AuthResult<AccessToken> = AuthResult.Success(AccessToken("fixture-token"))
        override suspend fun refreshSession(): AuthResult<AuthUser> = error("unused")
        override suspend fun logout(): AuthResult<Unit> = AuthResult.Success(Unit)
        override fun observeSession(): StateFlow<SessionState> = MutableStateFlow(SessionState.LoggedOut)
    }
}
