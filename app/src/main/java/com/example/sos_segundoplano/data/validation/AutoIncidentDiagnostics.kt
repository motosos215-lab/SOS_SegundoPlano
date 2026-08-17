package com.example.sos_segundoplano.data.validation

import android.util.Log
import com.example.sos_segundoplano.BuildConfig
import com.example.sos_segundoplano.domain.offline.OfflineQueueEnqueueResult
import com.example.sos_segundoplano.data.remote.incident.ManualSosAlertSummaryDto
import com.example.sos_segundoplano.domain.validation.IncidentCause
import com.example.sos_segundoplano.domain.validation.IncidentRemoteCreationStatus

/** Debug-only, ID-free observability for automatic incident creation. */
object AutoIncidentDiagnostics {
    private const val TAG = "MotoSOS.AutoIncident"
    private val safeTypePattern = Regex("[A-Za-z0-9_.-]{1,80}")

    fun incidentStarted(cause: IncidentCause) {
        debug("event=auto_incident_started cause=${cause.toDiagnosticCause()}")
    }

    fun localPersistenceStarted() {
        debug("event=auto_incident_local_persist_started")
    }

    fun localPersistenceResult(result: OfflineQueueEnqueueResult) {
        val message = when (result) {
            is OfflineQueueEnqueueResult.PersistenceFailed ->
                "event=auto_incident_local_persist_result result=failure type=${safeType(result.sanitizedMessage)}"
            else -> "event=auto_incident_local_persist_result result=success"
        }
        debug(message)
    }

    fun remoteContext(remoteTripPresent: Boolean, locationPresent: Boolean) {
        debug(
            "event=auto_incident_remote_context " +
                "remote_trip_present=$remoteTripPresent location_present=$locationPresent"
        )
    }

    fun locationResult(available: Boolean) {
        debug("event=auto_incident_location_result result=${if (available) "success" else "unavailable"}")
    }

    fun mobileSosStarted() {
        debug("event=auto_incident_mobile_sos_started")
    }

    fun mobileSosSuccess(summary: ManualSosAlertSummaryDto?) {
        debug(
            "event=auto_incident_mobile_sos_result result=success " +
                "push_prepared=${summary?.pushPrepared ?: 0} " +
                "sms_prepared=${summary?.smsPrepared ?: 0} " +
                "email_prepared=${summary?.emailPrepared ?: 0} " +
                "total_prepared=${summary?.totalPrepared ?: 0}"
        )
    }

    fun remoteCreateStarted() {
        debug("event=auto_incident_remote_create_started")
    }

    fun remoteCreateResult(status: IncidentRemoteCreationStatus) {
        val message = when (status) {
            is IncidentRemoteCreationStatus.Success -> "event=auto_incident_mobile_sos_result result=success"
            is IncidentRemoteCreationStatus.HttpError ->
                "event=auto_incident_mobile_sos_result result=http_error status=${status.statusCode} code=${safeType(status.sanitizedMessage)}"
            is IncidentRemoteCreationStatus.NetworkUnavailable -> "event=auto_incident_mobile_sos_result result=network_error"
            is IncidentRemoteCreationStatus.Timeout -> "event=auto_incident_mobile_sos_result result=timeout"
            is IncidentRemoteCreationStatus.InvalidResponse ->
                "event=auto_incident_mobile_sos_result result=invalid_response code=${safeType(status.sanitizedMessage)}"
            is IncidentRemoteCreationStatus.MissingRequiredData ->
                "event=auto_incident_mobile_sos_result result=missing_required_data code=${safeType(status.sanitizedMessage)}"
            IncidentRemoteCreationStatus.NotRequested -> "event=auto_incident_mobile_sos_result result=not_requested"
            IncidentRemoteCreationStatus.Pending -> "event=auto_incident_mobile_sos_result result=pending"
            IncidentRemoteCreationStatus.DuplicateAttempt -> "event=auto_incident_mobile_sos_result result=duplicate_attempt"
        }
        debug(message)
    }

    fun terminalState(state: String, reason: String? = null) {
        debug(
            listOfNotNull(
                "event=auto_incident_terminal_state",
                "state=$state",
                reason?.let { "reason=$it" }
            ).joinToString(" ")
        )
    }

    fun tripFinalizerTriggered() {
        debug("event=auto_incident_trip_finalizer_triggered")
    }

    fun tripFinishResult(result: String) {
        debug("event=auto_incident_trip_finish_result result=$result")
    }

    private fun IncidentCause.toDiagnosticCause(): String = when (this) {
        IncidentCause.Timeout -> "countdown_timeout"
        IncidentCause.UserRequestedHelp -> "user_requested_help"
        IncidentCause.CriticalPhysicalEvent -> "critical_physical_event"
        IncidentCause.ManualSos -> "manual_sos"
    }

    private fun safeType(value: String?): String = value
        ?.takeIf { it.matches(safeTypePattern) }
        ?: "none"

    private fun debug(message: String) {
        if (!BuildConfig.DEBUG) return
        runCatching { Log.d(TAG, message) }
    }
}
