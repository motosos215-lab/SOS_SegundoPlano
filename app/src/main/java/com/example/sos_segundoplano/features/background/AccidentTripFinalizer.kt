package com.example.sos_segundoplano.features.background

import com.example.sos_segundoplano.data.remote.trip.FinishTripRequestDto
import com.example.sos_segundoplano.data.remote.trip.RemoteTripFinisher
import com.example.sos_segundoplano.data.remote.trip.TripMutationResult
import com.example.sos_segundoplano.data.validation.AutoIncidentDiagnostics
import com.example.sos_segundoplano.domain.validation.FalsePositiveValidationState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

class AccidentTripFinalizer(
    private val finishTrip: (String) -> Unit
) {
    private val finishedAccidents = LinkedHashSet<String>()

    fun onValidationStateChanged(state: FalsePositiveValidationState) {
        val key = state.persistedRealAccidentKey() ?: return
        if (!finishedAccidents.add(key)) return

        val bundleKey = when (state) {
            is FalsePositiveValidationState.IncidentGenerated -> state.bundleKey
            is FalsePositiveValidationState.ImmediateAlertRequested -> state.incident.clientIncidentId.orEmpty()
            else -> ""
        }
        if (bundleKey.isNotBlank()) {
            AutoIncidentDiagnostics.tripFinalizerTriggered()
            finishTrip(bundleKey)
        }
    }

    fun reset() {
        finishedAccidents.clear()
    }
}

/**
 * Closes the canonical Phone trip before monitoring stops and emits the Wear stop hint.
 * A remote-confirmed persisted incident is the only caller; safe confirmation never reaches this coordinator.
 */
internal class AccidentTripTerminationCoordinator(
    private val remoteTripFinisher: RemoteTripFinisher,
    private val hasActiveRemoteTrip: () -> Boolean,
    private val stopMonitoring: () -> Unit,
    private val nowUtc: () -> Instant = { Instant.now() }
) {
    private val mutex = Mutex()
    private var completed = false

    suspend fun finishAfterPersistedIncident(): Boolean = mutex.withLock {
        if (completed) {
            AutoIncidentDiagnostics.tripFinishResult("success")
            true
        } else if (!hasActiveRemoteTrip()) {
            AutoIncidentDiagnostics.tripFinishResult("not_confirmed")
            false
        } else {
            when (
                remoteTripFinisher.finishTrip(
                    FinishTripRequestDto(clientFinishedAtUtc = nowUtc().toString())
                )
            ) {
                is TripMutationResult.Success -> {
                    completed = true
                    stopMonitoring()
                    AutoIncidentDiagnostics.tripFinishResult("success")
                    true
                }
                else -> {
                    AutoIncidentDiagnostics.tripFinishResult("failure")
                    false
                }
            }
        }
    }
}

private fun FalsePositiveValidationState.persistedRealAccidentKey(): String? = when (this) {
    is FalsePositiveValidationState.IncidentGenerated ->
        "incident-${incident.sessionId}-${incident.assessmentId}-${incident.incidentId}-${incident.cause}"
    is FalsePositiveValidationState.ImmediateAlertRequested ->
        "immediate-${incident.sessionId}-${incident.assessmentId}-${incident.incidentId}-${incident.cause}"
    else -> null
}
