package com.example.sos_segundoplano.domain.push

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MonitorPushPayload(
    val notificationDeliveryAttemptId: String,
    val alertDispatchId: String?,
    val incidentId: String?,
    val channel: String,
    val title: String?,
    val body: String?
) {
    override fun toString(): String =
        "MonitorPushPayload(notificationDeliveryAttemptId=[REDACTED], " +
            "alertDispatchId=${alertDispatchId.redacted()}, incidentId=${incidentId.redacted()}, " +
            "channel=$channel, titlePresent=${!title.isNullOrBlank()}, bodyPresent=${!body.isNullOrBlank()})"
}

object MonitorPushPayloadParser {
    const val KEY_NOTIFICATION_DELIVERY_ATTEMPT_ID = "notificationDeliveryAttemptId"
    const val KEY_ALERT_DISPATCH_ID = "alertDispatchId"
    const val KEY_INCIDENT_ID = "incidentId"
    const val KEY_CHANNEL = "channel"
    const val EXPECTED_CHANNEL = "Push"

    fun parse(
        data: Map<String, String>,
        title: String? = null,
        body: String? = null
    ): MonitorPushPayload? {
        val attemptId = data[KEY_NOTIFICATION_DELIVERY_ATTEMPT_ID].nonBlank() ?: return null
        val channel = data[KEY_CHANNEL].nonBlank() ?: return null
        if (channel != EXPECTED_CHANNEL) return null
        return MonitorPushPayload(
            notificationDeliveryAttemptId = attemptId,
            alertDispatchId = data[KEY_ALERT_DISPATCH_ID].nonBlank(),
            incidentId = data[KEY_INCIDENT_ID].nonBlank(),
            channel = channel,
            title = title.nonBlank(),
            body = body.nonBlank()
        )
    }
}

data class PendingMonitorAlert(
    val notificationDeliveryAttemptId: String,
    val alertDispatchId: String?,
    val incidentId: String?,
    val receivedAtEpochMillis: Long
) {
    override fun toString(): String =
        "PendingMonitorAlert(notificationDeliveryAttemptId=[REDACTED], " +
            "alertDispatchId=${alertDispatchId.redacted()}, incidentId=${incidentId.redacted()}, " +
            "receivedAtEpochMillis=$receivedAtEpochMillis)"
}

interface PendingMonitorAlertStore {
    fun read(): PendingMonitorAlert?
    fun save(alert: PendingMonitorAlert): Boolean
    fun clear(): Boolean
}

fun interface MonitorAlertClock {
    fun nowEpochMillis(): Long
}

class PendingMonitorAlertCoordinator(
    private val store: PendingMonitorAlertStore,
    private val clock: MonitorAlertClock = MonitorAlertClock(System::currentTimeMillis)
) {
    private val mutablePendingAlerts = MutableStateFlow(store.read())
    val pendingAlerts: StateFlow<PendingMonitorAlert?> = mutablePendingAlerts.asStateFlow()

    fun record(payload: MonitorPushPayload): Boolean {
        val current = mutablePendingAlerts.value
        if (current?.notificationDeliveryAttemptId == payload.notificationDeliveryAttemptId) return true
        val pending = PendingMonitorAlert(
            notificationDeliveryAttemptId = payload.notificationDeliveryAttemptId,
            alertDispatchId = payload.alertDispatchId,
            incidentId = payload.incidentId,
            receivedAtEpochMillis = clock.nowEpochMillis()
        )
        return store.save(pending).also { saved ->
            if (saved) mutablePendingAlerts.value = pending
        }
    }

    fun pending(): PendingMonitorAlert? = mutablePendingAlerts.value

    fun clear(): Boolean = store.clear().also { cleared ->
        if (cleared) mutablePendingAlerts.value = null
    }
}

private fun String?.nonBlank(): String? = this?.trim()?.takeIf(String::isNotEmpty)

private fun String?.redacted(): String = if (this == null) "null" else "[REDACTED]"
