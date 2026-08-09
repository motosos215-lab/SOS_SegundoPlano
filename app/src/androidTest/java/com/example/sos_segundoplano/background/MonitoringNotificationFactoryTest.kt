package com.example.sos_segundoplano.background

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.example.sos_segundoplano.R
import com.example.sos_segundoplano.domain.rules.GpsQualityStatus
import com.example.sos_segundoplano.domain.rules.RiskLevel
import com.example.sos_segundoplano.domain.validation.AlertDispatchRequest
import com.example.sos_segundoplano.domain.validation.AlertPayloadSummary
import com.example.sos_segundoplano.domain.validation.AlertPriority
import com.example.sos_segundoplano.domain.validation.FalsePositiveValidationState
import com.example.sos_segundoplano.domain.validation.IncidentCause
import com.example.sos_segundoplano.domain.validation.LocalIncident
import com.example.sos_segundoplano.domain.validation.ValidationDecisionReason
import com.example.sos_segundoplano.domain.validation.ValidationEvidence
import com.example.sos_segundoplano.domain.validation.ValidationMetadata
import com.example.sos_segundoplano.domain.validation.ValidationOrigin
import com.example.sos_segundoplano.features.background.MonitoringNotificationFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitoringNotificationFactoryTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val testChannelId = "test_active_trip_monitoring"
    private val testEmergencyChannelId = "test_possible_accident_alerts"
    private val factory = MonitoringNotificationFactory(
        context = context,
        channelId = testChannelId,
        emergencyChannelId = testEmergencyChannelId,
        notificationId = 13017
    )

    @After
    fun tearDown() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.deleteNotificationChannel(testChannelId)
            notificationManager.deleteNotificationChannel(testEmergencyChannelId)
        }
    }

    @Test
    fun createChannelUsesLowImportanceAndDisablesBadge() {
        factory.createChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = notificationManager.getNotificationChannel(testChannelId)

            assertNotNull(channel)
            assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
            assertFalse(channel.canShowBadge())
            assertFalse(channel.shouldVibrate())

            val emergencyChannel = notificationManager.getNotificationChannel(testEmergencyChannelId)
            assertNotNull(emergencyChannel)
            assertEquals(NotificationManager.IMPORTANCE_HIGH, emergencyChannel.importance)
            assertTrue(emergencyChannel.canShowBadge())
            assertTrue(emergencyChannel.shouldVibrate())
        } else {
            assertTrue(factory.isChannelEnabled())
        }
    }

    @Test
    fun buildNotificationUsesExpectedContentAndBehavior() {
        val notification = factory.buildNotification()

        assertEquals(testChannelId, notification.channelId)
        assertEquals(R.drawable.ic_motosos_notification, notification.smallIcon.resId)
        assertEquals(
            context.getString(R.string.monitoring_notification_title),
            notification.extras.getString(Notification.EXTRA_TITLE)
        )
        assertEquals(
            context.getString(R.string.monitoring_notification_content),
            notification.extras.getString(Notification.EXTRA_TEXT)
        )
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(NotificationCompat.CATEGORY_SERVICE, notification.category)
        assertEquals(NotificationCompat.PRIORITY_LOW, notification.priority)
        assertEquals(NotificationCompat.VISIBILITY_PRIVATE, notification.visibility)
        assertNotNull(notification.contentIntent)
        assertEquals(0, notification.actions?.size ?: 0)
    }

    @Test
    fun countdownNotificationUsesAccidentContentActionsAndHighPriority() {
        val notification = factory.buildNotification(fakeCountdownState())

        assertEquals(testEmergencyChannelId, notification.channelId)
        assertEquals(
            context.getString(R.string.validation_countdown_title),
            notification.extras.getString(Notification.EXTRA_TITLE)
        )
        assertEquals(
            context.getString(R.string.validation_countdown_seconds, 20),
            notification.extras.getString(Notification.EXTRA_TEXT)
        )
        assertEquals(NotificationCompat.PRIORITY_HIGH, notification.priority)
        assertEquals(NotificationCompat.CATEGORY_ALARM, notification.category)
        assertFalse(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        val actions = notification.actions.orEmpty()
        assertEquals(2, actions.size)
        assertEquals(context.getString(R.string.validation_confirm_safe), actions[0].title.toString())
        assertEquals(context.getString(R.string.validation_request_help), actions[1].title.toString())
    }

    @Test
    fun incidentGeneratedNotificationDoesNotClaimRemoteDelivery() {
        val notification = factory.buildNotification(fakeIncidentState(IncidentCause.UserRequestedHelp))

        assertEquals(
            context.getString(R.string.validation_incident_generated),
            notification.extras.getString(Notification.EXTRA_TITLE)
        )
        assertEquals(
            context.getString(R.string.validation_help_requested),
            notification.extras.getString(Notification.EXTRA_TEXT)
        )
        val content = notification.extras.getString(Notification.EXTRA_TEXT).orEmpty()
        assertFalse(content.contains("SOS enviado", ignoreCase = true))
        assertFalse(content.contains("contactos avisados", ignoreCase = true))
        assertFalse(content.contains("emergencia enviada", ignoreCase = true))
        assertFalse(content.contains("alerta entregada", ignoreCase = true))
    }

    @Test
    fun notificationIdIsPositive() {
        assertTrue(factory.notificationId > 0)
        assertTrue(MonitoringNotificationFactory.NOTIFICATION_ID > 0)
    }

    private fun fakeCountdownState() = FalsePositiveValidationState.CountdownActive(
        assessment = com.example.sos_segundoplano.domain.rules.RiskAssessment(
            sessionId = 1L,
            assessmentId = 1L,
            windowId = 1L,
            startNanos = 1L,
            endNanos = 2L,
            score = 55,
            riskLevel = RiskLevel.Medium,
            confidence = 0.8,
            outcomes = emptyList(),
            contributions = emptyList(),
            gpsQuality = com.example.sos_segundoplano.domain.rules.GpsQualityEvaluation(GpsQualityStatus.Good, 4.0, 1L, 0.9),
            deviceReadiness = com.example.sos_segundoplano.domain.rules.DeviceReadinessEvaluation(
                batteryStatus = com.example.sos_segundoplano.domain.rules.BatteryReadinessStatus.Normal,
                batteryPercentage = 80,
                charging = false,
                connectivityStatus = com.example.sos_segundoplano.domain.rules.ConnectivityReadinessStatus.Available,
                connectivityValidated = true,
                transport = null,
                wearableStatus = null,
                canCommunicateLater = true,
                confidence = 0.8
            ),
            movementContinuity = com.example.sos_segundoplano.domain.rules.MovementContinuityState.Stopped,
            droppedProcessedWindows = 0L,
            lateWindows = 0L,
            droppedRawEvents = 0L,
            ruleSetVersion = "test-rules",
            partialWindow = false
        ),
        metadata = fakeMetadata(),
        evidence = ValidationEvidence(
            movementContinuity = com.example.sos_segundoplano.domain.rules.MovementContinuityState.Stopped,
            gpsQuality = GpsQualityStatus.Good,
            ruleSetVersion = "test-rules"
        ),
        startedAtElapsedRealtimeNanos = 1L,
        deadlineElapsedRealtimeNanos = 21_000_000_000L,
        remainingNanos = 20_000_000_000L
    )

    private fun fakeIncidentState(cause: IncidentCause): FalsePositiveValidationState.IncidentGenerated {
        val incident = LocalIncident(
            incidentId = 1L,
            sessionId = 1L,
            assessmentId = 1L,
            windowId = 1L,
            createdAtElapsedRealtimeNanos = 1L,
            cause = cause,
            score = 55,
            riskLevel = RiskLevel.Medium,
            confidence = 0.8,
            relevantOutcomes = emptyList(),
            ruleSetVersion = "test-rules",
            validationPolicyVersion = "test-policy",
            gpsQuality = GpsQualityStatus.Good
        )
        val request = AlertDispatchRequest(
            requestId = 1L,
            incidentId = incident.incidentId,
            sessionId = incident.sessionId,
            assessmentId = incident.assessmentId,
            priority = AlertPriority.High,
            reason = cause,
            createdAtElapsedRealtimeNanos = 1L,
            score = incident.score,
            confidence = incident.confidence,
            payload = AlertPayloadSummary(
                sessionId = incident.sessionId,
                assessmentId = incident.assessmentId,
                incidentId = incident.incidentId,
                score = incident.score,
                riskLevel = incident.riskLevel,
                cause = cause,
                policyVersion = incident.validationPolicyVersion
            )
        )
        return FalsePositiveValidationState.IncidentGenerated(incident, request, fakeMetadata())
    }

    private fun fakeMetadata() = ValidationMetadata(
        sessionId = 1L,
        assessmentId = 1L,
        windowId = 1L,
        timestampElapsedRealtimeNanos = 1L,
        reason = ValidationDecisionReason.CandidatePhysicalRisk,
        score = 55,
        confidence = 0.8,
        origin = ValidationOrigin.System,
        policyVersion = "test-policy"
    )
}
