package com.example.sos_segundoplano.data.wear

import com.example.sos_segundoplano.core.background.MonitoringServiceStartResult
import com.example.sos_segundoplano.core.background.MonitoringServiceStarter
import com.example.sos_segundoplano.core.background.MonitoringServiceStopResult
import com.example.sos_segundoplano.core.background.MonitoringServiceStopper
import com.example.sos_segundoplano.core.permissions.AppNotificationStatus
import com.example.sos_segundoplano.core.permissions.AppNotificationStatusProvider
import com.example.sos_segundoplano.core.permissions.BackgroundLocationPermissionStatus
import com.example.sos_segundoplano.core.permissions.BackgroundLocationPermissionStatusProvider
import com.example.sos_segundoplano.core.permissions.BluetoothRequirementStatus
import com.example.sos_segundoplano.core.permissions.BluetoothRequirementStatusProvider
import com.example.sos_segundoplano.data.remote.trip.RemoteTripSessionStore
import com.example.sos_segundoplano.data.remote.trip.FinishTripRequestDto
import com.example.sos_segundoplano.data.remote.trip.RemoteTripIdFinisher
import com.example.sos_segundoplano.data.remote.trip.ResolvedRemoteTripStarter
import com.example.sos_segundoplano.data.remote.trip.TripMutationResult
import com.example.sos_segundoplano.data.trip.TripSessionStore
import com.example.sos_segundoplano.domain.validation.IncidentRemoteCreationStatus
import com.example.sos_segundoplano.domain.validation.LocalIncident
import com.example.sos_segundoplano.domain.auth.SessionState
import com.example.sos_segundoplano.domain.auth.UserRole
import com.example.sos_segundoplano.domain.model.TripSessionState
import com.example.sos_segundoplano.domain.repository.AuthRepository
import com.example.sos_segundoplano.wearprotocol.PhoneActionResponse
import com.example.sos_segundoplano.wearprotocol.PhoneActionResult
import com.example.sos_segundoplano.wearprotocol.FinishTripActionRequest
import com.example.sos_segundoplano.wearprotocol.ManualSosActionRequest
import com.example.sos_segundoplano.wearprotocol.StartTripActionRequest
import com.example.sos_segundoplano.wearprotocol.TripStateRequest
import com.example.sos_segundoplano.wearprotocol.TripStateResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class WearPhoneActionCoordinator(
    private val authRepository: AuthRepository,
    private val remoteTripStore: RemoteTripSessionStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val startDependencies: WearStartTripDependencies? = null,
    private val finishDependencies: WearFinishTripDependencies? = null,
    private val manualSosDependencies: WearManualSosDependencies? = null,
) {
    private val startMutex = Mutex()
    private val finishMutex = Mutex()
    private val manualSosMutex = Mutex()
    suspend fun currentTripState(request: TripStateRequest): TripStateResponse {
        val access = when (val session = authRepository.observeSession().value) {
            is SessionState.Authenticated -> session.user.role
            is SessionState.Refreshing -> session.user.role
            else -> null
        }
        val result = when (access) {
            UserRole.Rider -> PhoneActionResult.OK
            null -> PhoneActionResult.NOT_AUTHENTICATED
            else -> PhoneActionResult.WRONG_ROLE
        }
        val remoteTripId = remoteTripStore.remoteTripId.value?.trim()?.takeIf { it.isNotEmpty() }
        return TripStateResponse(
            requestId = request.requestId,
            active = result == PhoneActionResult.OK && remoteTripId != null,
            remoteTripId = remoteTripId.takeIf { result == PhoneActionResult.OK },
            startedAtEpochMs = remoteTripStore.startedAtEpochMs.value.takeIf { result == PhoneActionResult.OK && remoteTripId != null },
            updatedAtEpochMs = clock(),
            result = result,
            sanitizedCode = if (result == PhoneActionResult.OK) null else "trip_state_not_available"
        )
    }

    suspend fun startTrip(request: StartTripActionRequest, respondedAtEpochMs: Long): PhoneActionResponse = startMutex.withLock {
        val role = currentRole()
        when (role) {
            null -> return response(request.requestId, PhoneActionResult.NOT_AUTHENTICATED, "trip_start_not_authenticated", respondedAtEpochMs)
            UserRole.Rider -> Unit
            else -> return response(request.requestId, PhoneActionResult.WRONG_ROLE, "trip_start_wrong_role", respondedAtEpochMs)
        }

        val dependencies = startDependencies
            ?: return response(request.requestId, PhoneActionResult.UNAVAILABLE, "action_not_available", respondedAtEpochMs)
        val cached = dependencies.commandStore.find(request.commandId, WearCommandAction.StartTrip)
        if (cached?.state == WearCommandState.TerminalSuccess) {
            return response(request.requestId, PhoneActionResult.OK, cached.sanitizedCode, respondedAtEpochMs, cached.remoteTripId)
        }
        if (cached?.state == WearCommandState.TerminalRejected) {
            return response(request.requestId, PhoneActionResult.PHONE_ACTION_REQUIRED, cached.sanitizedCode, respondedAtEpochMs, cached.remoteTripId)
        }

        val readinessCode = dependencies.readinessCode()
        if (readinessCode != null) {
            return dependencies.persist(
                request,
                WearCommandState.TerminalRejected,
                PhoneActionResult.PHONE_ACTION_REQUIRED,
                readinessCode,
                null,
                respondedAtEpochMs,
            )
        }

        val existingTripId = remoteTripStore.remoteTripId.value?.trim()?.takeIf { it.isNotEmpty() }
        if (existingTripId != null) {
            return dependencies.startMonitoring(request, existingTripId, respondedAtEpochMs)
        }

        val tripSession = dependencies.tripSessionStore.beginTripSession()
        return when (val mutation = dependencies.resolvedStarter.startTrip()) {
            is TripMutationResult.Success -> {
                val persistedTripId = remoteTripStore.remoteTripId.value?.trim()?.takeIf { it.isNotEmpty() }
                if (persistedTripId == null) {
                    dependencies.persist(request, WearCommandState.Retryable, PhoneActionResult.RETRYABLE_ERROR, "remote_trip_persistence_unavailable", null, respondedAtEpochMs)
                } else {
                    dependencies.startMonitoring(request, persistedTripId, respondedAtEpochMs)
                }
            }
            is TripMutationResult.MissingRequiredData -> {
                dependencies.tripSessionStore.setIdleIfMatches(tripSession.tripSessionKey)
                dependencies.persist(
                    request, WearCommandState.TerminalRejected, PhoneActionResult.PHONE_ACTION_REQUIRED,
                    mutation.sanitizedMessage ?: "trip_start_requirements_missing", null, respondedAtEpochMs,
                )
            }
            else -> dependencies.persist(
                request, WearCommandState.Retryable, PhoneActionResult.RETRYABLE_ERROR,
                mutation.sanitizedCode(), null, respondedAtEpochMs,
            )
        }
    }

    suspend fun finishTrip(request: FinishTripActionRequest, respondedAtEpochMs: Long): PhoneActionResponse = finishMutex.withLock {
        val role = currentRole()
        when (role) {
            null -> return response(request.requestId, PhoneActionResult.NOT_AUTHENTICATED, "trip_finish_not_authenticated", respondedAtEpochMs)
            UserRole.Rider -> Unit
            else -> return response(request.requestId, PhoneActionResult.WRONG_ROLE, "trip_finish_wrong_role", respondedAtEpochMs)
        }

        val dependencies = finishDependencies
            ?: return response(request.requestId, PhoneActionResult.UNAVAILABLE, "action_not_available", respondedAtEpochMs)
        val cached = dependencies.commandStore.find(request.commandId, WearCommandAction.FinishTrip)
        if (cached?.state == WearCommandState.TerminalSuccess) {
            return response(request.requestId, PhoneActionResult.OK, cached.sanitizedCode, respondedAtEpochMs, cached.remoteTripId)
        }
        if (cached?.state == WearCommandState.TerminalRejected) {
            return response(
                request.requestId,
                cached.sanitizedCode.toFinishTerminalResult(),
                cached.sanitizedCode,
                respondedAtEpochMs,
                cached.remoteTripId,
            )
        }

        if (cached?.state == WearCommandState.PartialSideEffect) {
            val finishedTripId = cached.remoteTripId?.trim()?.takeIf { it.isNotEmpty() }
                ?: return dependencies.persist(
                    request, WearCommandState.TerminalRejected, PhoneActionResult.NO_ACTIVE_TRIP,
                    "no_active_trip", null, respondedAtEpochMs,
                )
            if (request.remoteTripId != finishedTripId) {
                return dependencies.persist(
                    request, WearCommandState.TerminalRejected, PhoneActionResult.TRIP_MISMATCH,
                    "trip_mismatch", finishedTripId, respondedAtEpochMs,
                )
            }
            return dependencies.completeLocalFinishAfterRemoteSuccess(
                request = request,
                remoteTripId = finishedTripId,
                respondedAtEpochMs = respondedAtEpochMs,
                skipMonitoringStop = cached.sanitizedCode == "local_trip_cleanup_retry_required",
            )
        }

        val localTripId = remoteTripStore.remoteTripId.value?.trim()?.takeIf { it.isNotEmpty() }
            ?: return dependencies.persist(
                request, WearCommandState.TerminalRejected, PhoneActionResult.NO_ACTIVE_TRIP,
                "no_active_trip", null, respondedAtEpochMs,
            )
        if (request.remoteTripId != localTripId) {
            return dependencies.persist(
                request, WearCommandState.TerminalRejected, PhoneActionResult.TRIP_MISMATCH,
                "trip_mismatch", localTripId, respondedAtEpochMs,
            )
        }

        return when (val mutation = dependencies.finisher.finishTrip(localTripId, dependencies.finishRequestFactory())) {
            is TripMutationResult.Success -> dependencies.completeLocalFinishAfterRemoteSuccess(
                request = request,
                remoteTripId = localTripId,
                respondedAtEpochMs = respondedAtEpochMs,
            )
            else -> dependencies.persist(
                request, WearCommandState.Retryable, PhoneActionResult.RETRYABLE_ERROR,
                mutation.finishSanitizedCode(), localTripId, respondedAtEpochMs,
            )
        }
    }

    suspend fun manualSos(request: ManualSosActionRequest, respondedAtEpochMs: Long): PhoneActionResponse = manualSosMutex.withLock {
        val role = currentRole()
        when (role) {
            null -> return response(request.requestId, PhoneActionResult.NOT_AUTHENTICATED, "manual_sos_not_authenticated", respondedAtEpochMs)
            UserRole.Rider -> Unit
            else -> return response(request.requestId, PhoneActionResult.WRONG_ROLE, "manual_sos_wrong_role", respondedAtEpochMs)
        }

        val dependencies = manualSosDependencies
            ?: return response(request.requestId, PhoneActionResult.UNAVAILABLE, "action_not_available", respondedAtEpochMs)
        val cached = dependencies.commandStore.find(request.commandId, WearCommandAction.ManualSos)
        if (cached?.state == WearCommandState.TerminalSuccess) {
            return response(request.requestId, PhoneActionResult.OK, cached.sanitizedCode, respondedAtEpochMs, cached.remoteTripId)
        }
        if (cached?.state == WearCommandState.TerminalRejected) {
            return response(
                request.requestId,
                cached.sanitizedCode.toManualSosTerminalResult(),
                cached.sanitizedCode,
                respondedAtEpochMs,
                cached.remoteTripId,
            )
        }

        val localTripId = remoteTripStore.remoteTripId.value?.trim()?.takeIf { it.isNotEmpty() }
            ?: return dependencies.persist(
                request, WearCommandState.TerminalRejected, PhoneActionResult.NO_ACTIVE_TRIP,
                "no_active_trip", null, respondedAtEpochMs,
            )
        if (request.remoteTripId != localTripId) {
            return dependencies.persist(
                request, WearCommandState.TerminalRejected, PhoneActionResult.TRIP_MISMATCH,
                "trip_mismatch", localTripId, respondedAtEpochMs,
            )
        }

        val outcome = dependencies.requestManualSos().toWearManualSosOutcome()
        return dependencies.persist(
            request = request,
            state = outcome.state,
            result = outcome.result,
            sanitizedCode = outcome.sanitizedCode,
            remoteTripId = localTripId,
            respondedAtEpochMs = respondedAtEpochMs,
        )
    }

    private fun currentRole(): UserRole? = when (val session = authRepository.observeSession().value) {
        is SessionState.Authenticated -> session.user.role
        is SessionState.Refreshing -> session.user.role
        else -> null
    }

    private fun response(
        requestId: String,
        result: PhoneActionResult,
        sanitizedCode: String?,
        respondedAtEpochMs: Long,
        remoteTripId: String? = null,
    ) = PhoneActionResponse(requestId, result, sanitizedCode, remoteTripId, respondedAtEpochMs)
}

class WearStartTripDependencies(
    val resolvedStarter: ResolvedRemoteTripStarter,
    val monitoringServiceStarter: MonitoringServiceStarter,
    val tripSessionStore: TripSessionStore,
    val locationStatusProvider: BackgroundLocationPermissionStatusProvider,
    val notificationStatusProvider: AppNotificationStatusProvider,
    val bluetoothStatusProvider: BluetoothRequirementStatusProvider,
    val commandStore: WearCommandResultStore,
    private val now: () -> Long,
) {
    fun readinessCode(): String? = when {
        locationStatusProvider.getStatus() != BackgroundLocationPermissionStatus.Granted -> "location_permission_required"
        notificationStatusProvider.getStatus() != AppNotificationStatus.Enabled -> "notification_permission_required"
        else -> when (bluetoothStatusProvider.getStatus()) {
            BluetoothRequirementStatus.Enabled -> null
            BluetoothRequirementStatus.PermissionMissing -> "bluetooth_permission_required"
            BluetoothRequirementStatus.Disabled -> "bluetooth_disabled"
            BluetoothRequirementStatus.Unsupported -> "bluetooth_unsupported"
        }
    }

    fun startMonitoring(request: StartTripActionRequest, remoteTripId: String, respondedAtEpochMs: Long): PhoneActionResponse =
        when (monitoringServiceStarter.start()) {
            MonitoringServiceStartResult.Started -> {
                tripSessionStore.beginTripSession()
                persist(request, WearCommandState.TerminalSuccess, PhoneActionResult.OK, null, remoteTripId, respondedAtEpochMs)
            }
            MonitoringServiceStartResult.Failed -> persist(
                request, WearCommandState.PartialSideEffect, PhoneActionResult.RETRYABLE_ERROR,
                "monitoring_start_retry_required", remoteTripId, respondedAtEpochMs,
            )
        }

    fun persist(
        request: StartTripActionRequest,
        state: WearCommandState,
        result: PhoneActionResult,
        sanitizedCode: String?,
        remoteTripId: String?,
        respondedAtEpochMs: Long,
    ): PhoneActionResponse {
        val recordedAtEpochMs = now()
        commandStore.save(
            WearCommandRecord(
                commandId = request.commandId,
                action = WearCommandAction.StartTrip,
                state = state,
                sanitizedCode = sanitizedCode,
                remoteTripId = remoteTripId,
                createdAtEpochMs = recordedAtEpochMs,
                updatedAtEpochMs = recordedAtEpochMs,
            ),
        )
        return PhoneActionResponse(request.requestId, result, sanitizedCode, remoteTripId, respondedAtEpochMs)
    }
}

class WearFinishTripDependencies(
    val finisher: RemoteTripIdFinisher,
    val monitoringServiceStopper: MonitoringServiceStopper,
    val tripSessionStore: TripSessionStore,
    val commandStore: WearCommandResultStore,
    val finishRequestFactory: () -> FinishTripRequestDto,
    val reconcileLocalFinishedTrip: (String) -> Boolean,
    private val now: () -> Long,
) {
    fun completeLocalFinishAfterRemoteSuccess(
        request: FinishTripActionRequest,
        remoteTripId: String,
        respondedAtEpochMs: Long,
        skipMonitoringStop: Boolean = false,
    ): PhoneActionResponse {
        if (!skipMonitoringStop) {
            when (monitoringServiceStopper.stop()) {
                MonitoringServiceStopResult.Failed -> return persist(
                    request, WearCommandState.PartialSideEffect, PhoneActionResult.RETRYABLE_ERROR,
                    "monitoring_stop_retry_required_after_remote_finish", remoteTripId, respondedAtEpochMs,
                )
                MonitoringServiceStopResult.Stopped,
                MonitoringServiceStopResult.AlreadyStopped -> Unit
            }
        }

        if (!reconcileLocalFinishedTrip(remoteTripId)) {
            return persist(
                request, WearCommandState.PartialSideEffect, PhoneActionResult.RETRYABLE_ERROR,
                "local_trip_cleanup_retry_required", remoteTripId, respondedAtEpochMs,
            )
        }
        return persist(
            request, WearCommandState.TerminalSuccess, PhoneActionResult.OK,
            null, null, respondedAtEpochMs,
        )
    }

    fun persist(
        request: FinishTripActionRequest,
        state: WearCommandState,
        result: PhoneActionResult,
        sanitizedCode: String?,
        remoteTripId: String?,
        respondedAtEpochMs: Long,
    ): PhoneActionResponse {
        val recordedAtEpochMs = now()
        commandStore.save(
            WearCommandRecord(
                commandId = request.commandId,
                action = WearCommandAction.FinishTrip,
                state = state,
                sanitizedCode = sanitizedCode,
                remoteTripId = remoteTripId,
                createdAtEpochMs = recordedAtEpochMs,
                updatedAtEpochMs = recordedAtEpochMs,
            ),
        )
        return PhoneActionResponse(request.requestId, result, sanitizedCode, remoteTripId, respondedAtEpochMs)
    }
}

class WearManualSosDependencies(
    private val manualSosRequester: suspend () -> LocalIncident,
    val commandStore: WearCommandResultStore,
    private val now: () -> Long,
) {
    suspend fun requestManualSos(): LocalIncident = manualSosRequester()

    fun persist(
        request: ManualSosActionRequest,
        state: WearCommandState,
        result: PhoneActionResult,
        sanitizedCode: String?,
        remoteTripId: String?,
        respondedAtEpochMs: Long,
    ): PhoneActionResponse {
        val recordedAtEpochMs = now()
        commandStore.save(
            WearCommandRecord(
                commandId = request.commandId,
                action = WearCommandAction.ManualSos,
                state = state,
                sanitizedCode = sanitizedCode,
                remoteTripId = remoteTripId,
                createdAtEpochMs = recordedAtEpochMs,
                updatedAtEpochMs = recordedAtEpochMs,
            ),
        )
        return PhoneActionResponse(request.requestId, result, sanitizedCode, remoteTripId, respondedAtEpochMs)
    }
}

private fun TripMutationResult.sanitizedCode(): String = when (this) {
    is TripMutationResult.HttpError -> sanitizedMessage ?: "trip_start_rejected"
    is TripMutationResult.NetworkUnavailable -> sanitizedMessage ?: "network_unavailable"
    is TripMutationResult.Timeout -> sanitizedMessage ?: "network_timeout"
    is TripMutationResult.InvalidResponse -> sanitizedMessage ?: "trip_start_response_invalid"
    is TripMutationResult.MissingRequiredData -> sanitizedMessage ?: "trip_start_requirements_missing"
    is TripMutationResult.Success -> "trip_start_state_invalid"
}

private fun String?.toFinishTerminalResult(): PhoneActionResult = when (this) {
    "no_active_trip" -> PhoneActionResult.NO_ACTIVE_TRIP
    "trip_mismatch" -> PhoneActionResult.TRIP_MISMATCH
    else -> PhoneActionResult.RETRYABLE_ERROR
}

private fun String?.toManualSosTerminalResult(): PhoneActionResult = when (this) {
    "no_active_trip" -> PhoneActionResult.NO_ACTIVE_TRIP
    "trip_mismatch" -> PhoneActionResult.TRIP_MISMATCH
    "manual_sos_location_missing" -> PhoneActionResult.PHONE_ACTION_REQUIRED
    else -> PhoneActionResult.RETRYABLE_ERROR
}

private data class WearManualSosOutcome(
    val state: WearCommandState,
    val result: PhoneActionResult,
    val sanitizedCode: String?,
)

private fun LocalIncident.toWearManualSosOutcome(): WearManualSosOutcome = when (val status = remoteCreationStatus) {
    is IncidentRemoteCreationStatus.Success -> WearManualSosOutcome(
        WearCommandState.TerminalSuccess,
        PhoneActionResult.OK,
        null,
    )
    is IncidentRemoteCreationStatus.MissingRequiredData -> WearManualSosOutcome(
        WearCommandState.TerminalRejected,
        PhoneActionResult.PHONE_ACTION_REQUIRED,
        status.sanitizedMessage ?: "manual_sos_requirements_missing",
    )
    is IncidentRemoteCreationStatus.HttpError -> WearManualSosOutcome(
        WearCommandState.Retryable,
        PhoneActionResult.RETRYABLE_ERROR,
        status.sanitizedMessage ?: "manual_sos_request_failed",
    )
    is IncidentRemoteCreationStatus.NetworkUnavailable -> WearManualSosOutcome(
        WearCommandState.Retryable,
        PhoneActionResult.RETRYABLE_ERROR,
        status.sanitizedMessage ?: "network_unavailable",
    )
    is IncidentRemoteCreationStatus.Timeout -> WearManualSosOutcome(
        WearCommandState.Retryable,
        PhoneActionResult.RETRYABLE_ERROR,
        status.sanitizedMessage ?: "network_timeout",
    )
    is IncidentRemoteCreationStatus.InvalidResponse -> WearManualSosOutcome(
        if (status.sanitizedMessage == "manual_sos_result_persistence_failed") {
            WearCommandState.PartialSideEffect
        } else {
            WearCommandState.Retryable
        },
        PhoneActionResult.RETRYABLE_ERROR,
        status.sanitizedMessage ?: "manual_sos_response_invalid",
    )
    IncidentRemoteCreationStatus.NotRequested,
    IncidentRemoteCreationStatus.Pending,
    IncidentRemoteCreationStatus.DuplicateAttempt -> WearManualSosOutcome(
        WearCommandState.Retryable,
        PhoneActionResult.RETRYABLE_ERROR,
        "manual_sos_retry_required",
    )
}

private fun TripMutationResult.finishSanitizedCode(): String = when (this) {
    is TripMutationResult.HttpError -> sanitizedMessage ?: "trip_finish_rejected"
    is TripMutationResult.NetworkUnavailable -> sanitizedMessage ?: "network_unavailable"
    is TripMutationResult.Timeout -> sanitizedMessage ?: "network_timeout"
    is TripMutationResult.InvalidResponse -> sanitizedMessage ?: "trip_finish_response_invalid"
    is TripMutationResult.MissingRequiredData -> sanitizedMessage ?: "trip_finish_requirements_missing"
    is TripMutationResult.Success -> "trip_finish_state_invalid"
}
