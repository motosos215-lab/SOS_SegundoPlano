package com.example.sos_segundoplano.push

import com.example.sos_segundoplano.domain.push.RiderMonitorFeedbackClock
import com.example.sos_segundoplano.domain.push.RiderMonitorFeedbackCoordinator
import com.example.sos_segundoplano.domain.push.RiderMonitorFeedbackMessage
import com.example.sos_segundoplano.domain.push.RiderMonitorFeedbackPayloadParser
import com.example.sos_segundoplano.domain.push.RiderMonitorFeedbackStore
import com.example.sos_segundoplano.domain.push.RiderMonitorFeedbackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RiderMonitorFeedbackTest {
    @Test fun parsesThreeBackendFeedbackEvents() {
        RiderMonitorFeedbackType.entries.forEach { type ->
            val payload = RiderMonitorFeedbackPayloadParser.parse(
                data = baseData(type.wireName)
            )
            assertNotNull(payload)
            assertEquals(type, payload?.type)
            assertEquals("rider-notification-attempt", payload?.notificationDeliveryAttemptId)
            assertEquals("emergency_status", payload?.screen)
        }
    }

    @Test fun unrelatedPushIsNotTreatedAsRiderFeedback() {
        assertNull(RiderMonitorFeedbackPayloadParser.parse(mapOf("eventType" to "other_event")))
        assertNull(RiderMonitorFeedbackPayloadParser.parse(baseData("monitor_alert_acknowledged") - "notificationDeliveryAttemptId"))
    }

    @Test fun coordinatorDeduplicatesByRiderNotificationAttemptAndTracksUnread() {
        val store = FakeFeedbackStore()
        val coordinator = RiderMonitorFeedbackCoordinator(store, RiderMonitorFeedbackClock { 1234L })
        val payload = requireNotNull(RiderMonitorFeedbackPayloadParser.parse(baseData("monitor_alert_acknowledged")))

        assertTrue(coordinator.record("rider-1", payload))
        assertTrue(coordinator.record("rider-1", payload))
        assertEquals(1, coordinator.messagesFor("rider-1").size)
        assertFalse(coordinator.messagesFor("rider-1").single().isRead)

        assertTrue(coordinator.markAllRead("rider-1"))
        assertTrue(coordinator.messagesFor("rider-1").single().isRead)
    }

    private fun baseData(eventType: String) = mapOf(
        "eventType" to eventType,
        "notificationDeliveryAttemptId" to "rider-notification-attempt",
        "incidentId" to "incident-id",
        "alertDispatchId" to "dispatch-id",
        "monitorAlertAttemptId" to "monitor-attempt-id",
        "monitorUserId" to "monitor-user-id",
        "occurredAtUtc" to "2026-08-18T20:00:00Z",
        "screen" to "emergency_status"
    )
}

private class FakeFeedbackStore : RiderMonitorFeedbackStore {
    private var state = emptyList<RiderMonitorFeedbackMessage>()
    override fun readAll(): List<RiderMonitorFeedbackMessage> = state
    override fun saveAll(messages: List<RiderMonitorFeedbackMessage>): Boolean {
        state = messages
        return true
    }
}
