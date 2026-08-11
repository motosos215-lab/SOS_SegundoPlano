package com.example.sos_segundoplano.push

import com.example.sos_segundoplano.domain.push.MonitorPushPayload
import com.example.sos_segundoplano.domain.push.MonitorPushPayloadParser
import com.example.sos_segundoplano.domain.push.PendingMonitorAlertCoordinator

data class MonitorAlertHandlingResult(
    val validPayload: Boolean,
    val stored: Boolean,
    val notificationResult: MonitorAlertNotificationResult?
)

class MonitorAlertMessageHandler(
    private val coordinator: PendingMonitorAlertCoordinator,
    private val presenter: (MonitorPushPayload) -> MonitorAlertNotificationResult
) {
    fun handle(
        data: Map<String, String>,
        title: String?,
        body: String?
    ): MonitorAlertHandlingResult {
        val payload = MonitorPushPayloadParser.parse(data, title, body)
            ?: return MonitorAlertHandlingResult(
                validPayload = false,
                stored = false,
                notificationResult = null
            )
        return MonitorAlertHandlingResult(
            validPayload = true,
            stored = coordinator.record(payload),
            notificationResult = presenter(payload)
        )
    }
}
