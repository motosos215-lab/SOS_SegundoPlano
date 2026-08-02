package com.example.sos_segundoplano.data.offline

import com.example.sos_segundoplano.domain.offline.WallClock
import com.example.sos_segundoplano.domain.rules.GpsQualityStatus
import com.example.sos_segundoplano.domain.rules.RiskLevel
import com.example.sos_segundoplano.domain.validation.AlertDispatchRequest
import com.example.sos_segundoplano.domain.validation.AlertPayloadSummary
import com.example.sos_segundoplano.domain.validation.AlertPriority
import com.example.sos_segundoplano.domain.validation.IncidentCause
import com.example.sos_segundoplano.domain.validation.LocalIncident
import com.example.sos_segundoplano.domain.validation.MinorEvent
import com.example.sos_segundoplano.domain.validation.MinorEventType
import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineQueueMappersTest {
    private val clock = WallClock { 123_456L }

    @Test fun mapsMinorIncidentAndAlertRequestWithWallClockTimestamp() {
        val minor = MinorEvent(1L, 2L, 3L, 4L, MinorEventType.Bump, 9L, 30, 0.8, evidence(), "policy")
        val incident = LocalIncident(5L, 2L, 3L, 4L, 10L, IncidentCause.Timeout, 70, RiskLevel.High, 0.9, emptyList(), "rules", "policy", GpsQualityStatus.Good)
        val request = AlertDispatchRequest(
            requestId = 6L,
            incidentId = 5L,
            sessionId = 2L,
            assessmentId = 3L,
            priority = AlertPriority.High,
            reason = IncidentCause.Timeout,
            createdAtElapsedRealtimeNanos = 11L,
            score = 70,
            confidence = 0.9,
            payload = AlertPayloadSummary(2L, 3L, 5L, 70, RiskLevel.High, IncidentCause.Timeout, "policy")
        )

        assertEquals(123_456L, minor.toSyncPayload(clock).payload.occurredAtEpochMillis)
        assertEquals(5L, incident.toSyncPayload(clock).payload.incidentId)
        assertEquals("Pending", request.toSyncPayload(clock).payload.deliveryStatus)
    }

    private fun evidence() = com.example.sos_segundoplano.domain.validation.ValidationEvidence(
        movementContinuity = com.example.sos_segundoplano.domain.rules.MovementContinuityState.Continuing,
        gpsQuality = GpsQualityStatus.Good,
        ruleSetVersion = "rules"
    )
}
