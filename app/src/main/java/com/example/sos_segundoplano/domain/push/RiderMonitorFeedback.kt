package com.example.sos_segundoplano.domain.push

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RiderMonitorFeedbackType(val wireName: String) {
    Viewed("monitor_alert_viewed"),
    Acknowledged("monitor_alert_acknowledged"),
    Declined("monitor_alert_declined");

    companion object {
        fun fromWireName(value: String?): RiderMonitorFeedbackType? = entries.firstOrNull {
            it.wireName.equals(value?.trim(), ignoreCase = true)
        }
    }
}

data class RiderMonitorFeedbackPayload(
    val type: RiderMonitorFeedbackType,
    val notificationDeliveryAttemptId: String,
    val incidentId: String?,
    val alertDispatchId: String?,
    val monitorAlertAttemptId: String?,
    val monitorUserId: String?,
    val occurredAtUtc: String?,
    val screen: String?,
    val title: String?,
    val body: String?
) {
    override fun toString(): String =
        "RiderMonitorFeedbackPayload(type=$type, notificationDeliveryAttemptId=[REDACTED], " +
            "incidentId=${incidentId.redacted()}, alertDispatchId=${alertDispatchId.redacted()}, " +
            "monitorAlertAttemptId=${monitorAlertAttemptId.redacted()}, monitorUserId=${monitorUserId.redacted()}, " +
            "occurredAtUtc=$occurredAtUtc, screen=$screen, titlePresent=${!title.isNullOrBlank()}, " +
            "bodyPresent=${!body.isNullOrBlank()})"
}

object RiderMonitorFeedbackPayloadParser {
    const val KEY_EVENT_TYPE = "eventType"
    const val KEY_NOTIFICATION_DELIVERY_ATTEMPT_ID = "notificationDeliveryAttemptId"
    const val KEY_INCIDENT_ID = "incidentId"
    const val KEY_ALERT_DISPATCH_ID = "alertDispatchId"
    const val KEY_MONITOR_ALERT_ATTEMPT_ID = "monitorAlertAttemptId"
    const val KEY_MONITOR_USER_ID = "monitorUserId"
    const val KEY_OCCURRED_AT_UTC = "occurredAtUtc"
    const val KEY_SCREEN = "screen"
    const val EXPECTED_SCREEN = "emergency_status"

    fun parse(
        data: Map<String, String>,
        title: String? = null,
        body: String? = null
    ): RiderMonitorFeedbackPayload? {
        val type = RiderMonitorFeedbackType.fromWireName(data[KEY_EVENT_TYPE]) ?: return null
        val notificationAttemptId = data[KEY_NOTIFICATION_DELIVERY_ATTEMPT_ID].nonBlank() ?: return null
        val screen = data[KEY_SCREEN].nonBlank()
        if (screen != null && !screen.equals(EXPECTED_SCREEN, ignoreCase = true)) return null
        return RiderMonitorFeedbackPayload(
            type = type,
            notificationDeliveryAttemptId = notificationAttemptId,
            incidentId = data[KEY_INCIDENT_ID].nonBlank(),
            alertDispatchId = data[KEY_ALERT_DISPATCH_ID].nonBlank(),
            monitorAlertAttemptId = data[KEY_MONITOR_ALERT_ATTEMPT_ID].nonBlank(),
            monitorUserId = data[KEY_MONITOR_USER_ID].nonBlank(),
            occurredAtUtc = data[KEY_OCCURRED_AT_UTC].nonBlank(),
            screen = screen,
            title = title.nonBlank(),
            body = body.nonBlank()
        )
    }
}

data class RiderMonitorFeedbackMessage(
    val ownerUserId: String,
    val type: RiderMonitorFeedbackType,
    val notificationDeliveryAttemptId: String,
    val incidentId: String?,
    val alertDispatchId: String?,
    val monitorAlertAttemptId: String?,
    val occurredAtUtc: String?,
    val receivedAtEpochMillis: Long,
    val body: String?,
    val isRead: Boolean
) {
    override fun toString(): String =
        "RiderMonitorFeedbackMessage(ownerUserId=[REDACTED], type=$type, " +
            "notificationDeliveryAttemptId=[REDACTED], incidentId=${incidentId.redacted()}, " +
            "alertDispatchId=${alertDispatchId.redacted()}, monitorAlertAttemptId=${monitorAlertAttemptId.redacted()}, " +
            "occurredAtUtc=$occurredAtUtc, receivedAtEpochMillis=$receivedAtEpochMillis, " +
            "bodyPresent=${!body.isNullOrBlank()}, isRead=$isRead)"
}

interface RiderMonitorFeedbackStore {
    fun readAll(): List<RiderMonitorFeedbackMessage>
    fun saveAll(messages: List<RiderMonitorFeedbackMessage>): Boolean
}

fun interface RiderMonitorFeedbackClock {
    fun nowEpochMillis(): Long
}

class RiderMonitorFeedbackCoordinator(
    private val store: RiderMonitorFeedbackStore,
    private val clock: RiderMonitorFeedbackClock = RiderMonitorFeedbackClock(System::currentTimeMillis),
    private val maxMessages: Int = 50
) {
    private val mutableMessages = MutableStateFlow(store.readAll().sortedByDescending { it.receivedAtEpochMillis })
    val messages: StateFlow<List<RiderMonitorFeedbackMessage>> = mutableMessages.asStateFlow()

    init {
        require(maxMessages > 0)
    }

    fun record(ownerUserId: String, payload: RiderMonitorFeedbackPayload): Boolean {
        val owner = ownerUserId.trim()
        if (owner.isEmpty()) return false
        val current = mutableMessages.value
        if (current.any { it.notificationDeliveryAttemptId == payload.notificationDeliveryAttemptId }) return true
        val message = RiderMonitorFeedbackMessage(
            ownerUserId = owner,
            type = payload.type,
            notificationDeliveryAttemptId = payload.notificationDeliveryAttemptId,
            incidentId = payload.incidentId,
            alertDispatchId = payload.alertDispatchId,
            monitorAlertAttemptId = payload.monitorAlertAttemptId,
            occurredAtUtc = payload.occurredAtUtc,
            receivedAtEpochMillis = clock.nowEpochMillis(),
            body = payload.body?.take(500),
            isRead = false
        )
        val updated = (listOf(message) + current).take(maxMessages)
        return store.saveAll(updated).also { saved -> if (saved) mutableMessages.value = updated }
    }

    fun markAllRead(ownerUserId: String): Boolean {
        val owner = ownerUserId.trim()
        if (owner.isEmpty()) return false
        val current = mutableMessages.value
        val updated = current.map { if (it.ownerUserId == owner) it.copy(isRead = true) else it }
        if (updated == current) return true
        return store.saveAll(updated).also { saved -> if (saved) mutableMessages.value = updated }
    }

    fun messagesFor(ownerUserId: String): List<RiderMonitorFeedbackMessage> =
        mutableMessages.value.filter { it.ownerUserId == ownerUserId }
}

private fun String?.nonBlank(): String? = this?.trim()?.takeIf(String::isNotEmpty)
private fun String?.redacted(): String = if (this == null) "null" else "[REDACTED]"
