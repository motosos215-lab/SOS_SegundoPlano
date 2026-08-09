package com.example.sos_segundoplano.features.background

import com.example.sos_segundoplano.domain.validation.FalsePositiveValidationState

class AccidentTripFinalizer(
    private val finishTrip: () -> Unit
) {
    private val finishedAccidents = LinkedHashSet<String>()

    fun onValidationStateChanged(state: FalsePositiveValidationState) {
        val key = state.persistedRealAccidentKey() ?: return
        if (finishedAccidents.add(key)) {
            finishTrip()
        }
    }

    fun reset() {
        finishedAccidents.clear()
    }
}

private fun FalsePositiveValidationState.persistedRealAccidentKey(): String? = when (this) {
    is FalsePositiveValidationState.IncidentGenerated ->
        "incident-${incident.sessionId}-${incident.assessmentId}-${incident.incidentId}-${incident.cause}"
    is FalsePositiveValidationState.ImmediateAlertRequested ->
        "immediate-${incident.sessionId}-${incident.assessmentId}-${incident.incidentId}-${incident.cause}"
    else -> null
}
