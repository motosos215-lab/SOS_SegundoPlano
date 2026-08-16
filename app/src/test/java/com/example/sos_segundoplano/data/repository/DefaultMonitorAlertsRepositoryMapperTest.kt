package com.example.sos_segundoplano.data.repository

import com.example.sos_segundoplano.data.remote.monitor.MonitorAlertAcknowledgementDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultMonitorAlertsRepositoryMapperTest {
    @Test fun partialHistoryAlertWithAttemptIdRemainsActionable() {
        val mapped = requireNotNull(dto(
            id = null,
            alertDispatchId = null,
            incidentId = null,
            tripId = null,
            emergencyContactId = null,
            status = null,
            responseType = null,
            createdAtUtc = null
        ).toHistoryDomain())
        assertEquals("attempt-1", mapped.notificationDeliveryAttemptId)
        assertNull(mapped.tripId)
        assertNull(mapped.emergencyContactId)
        assertNull(mapped.alertDispatchId)
        assertNull(mapped.incidentId)
        assertNull(mapped.status)
        assertNull(mapped.responseType)
        assertNull(mapped.createdAtUtc)
    }

    @Test fun missingAttemptIdIsTheOnlyReasonToDiscardHistoryAlert() {
        assertNull(dto(notificationDeliveryAttemptId = null).toHistoryDomain())

        val visible = listOf(
            dto(notificationDeliveryAttemptId = "attempt-complete"),
            dto(notificationDeliveryAttemptId = "attempt-partial", tripId = null),
            dto(notificationDeliveryAttemptId = null)
        ).mapNotNull { it.toHistoryDomain() }

        assertEquals(listOf("attempt-complete", "attempt-partial"), visible.map { it.notificationDeliveryAttemptId })
        assertTrue(visible[1].tripId == null)
    }

    private fun dto(
        id: String? = "id-1",
        alertDispatchId: String? = "dispatch-1",
        notificationDeliveryAttemptId: String? = "attempt-1",
        incidentId: String? = "incident-1",
        tripId: String? = "trip-1",
        emergencyContactId: String? = "contact-1",
        status: String? = "Pending",
        responseType: String? = "None",
        createdAtUtc: String? = "2026-08-12T16:51:09Z"
    ) = MonitorAlertAcknowledgementDto(
        id = id,
        alertDispatchId = alertDispatchId,
        notificationDeliveryAttemptId = notificationDeliveryAttemptId,
        incidentId = incidentId,
        tripId = tripId,
        emergencyContactId = emergencyContactId,
        status = status,
        responseType = responseType,
        createdAtUtc = createdAtUtc
    )
}
