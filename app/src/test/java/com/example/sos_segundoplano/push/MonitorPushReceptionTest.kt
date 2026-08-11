package com.example.sos_segundoplano.push

import com.example.sos_segundoplano.domain.auth.UserRole
import com.example.sos_segundoplano.domain.push.MonitorAlertClock
import com.example.sos_segundoplano.domain.push.MonitorPushPayloadParser
import com.example.sos_segundoplano.domain.push.PendingMonitorAlert
import com.example.sos_segundoplano.domain.push.PendingMonitorAlertCoordinator
import com.example.sos_segundoplano.domain.push.PendingMonitorAlertStore
import com.example.sos_segundoplano.features.auth.AuthEntryStatus
import com.example.sos_segundoplano.features.auth.LoginUiState
import com.example.sos_segundoplano.features.auth.RootDestination
import com.example.sos_segundoplano.features.auth.resolveRootDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorPushReceptionTest {
    @Test fun confirmedPayloadParsesOnlyConfirmedFields() {
        val payload = MonitorPushPayloadParser.parse(
            data = validData() + ("unknownBackendField" to "ignored"),
            title = "MotoSOS Alert",
            body = "Emergency alert available in MotoSOS."
        )

        assertNotNull(payload)
        assertEquals(ATTEMPT_ID, payload?.notificationDeliveryAttemptId)
        assertEquals(ALERT_DISPATCH_ID, payload?.alertDispatchId)
        assertEquals(INCIDENT_ID, payload?.incidentId)
        assertEquals("Push", payload?.channel)
        assertEquals("MotoSOS Alert", payload?.title)
        assertEquals("Emergency alert available in MotoSOS.", payload?.body)
        assertFalse(payload.toString().contains("unknownBackendField"))
    }

    @Test fun missingAttemptOrUnexpectedChannelIsRejectedWithoutCrash() {
        assertNull(
            MonitorPushPayloadParser.parse(
                validData() - MonitorPushPayloadParser.KEY_NOTIFICATION_DELIVERY_ATTEMPT_ID
            )
        )
        assertNull(
            MonitorPushPayloadParser.parse(
                validData() + (MonitorPushPayloadParser.KEY_CHANNEL to "Email")
            )
        )
        assertNull(MonitorPushPayloadParser.parse(emptyMap()))
    }

    @Test fun optionalConfirmedFieldsMayBeMissingWithoutLosingAttempt() {
        val payload = MonitorPushPayloadParser.parse(
            mapOf(
                MonitorPushPayloadParser.KEY_NOTIFICATION_DELIVERY_ATTEMPT_ID to ATTEMPT_ID,
                MonitorPushPayloadParser.KEY_CHANNEL to "Push"
            )
        )

        assertEquals(ATTEMPT_ID, payload?.notificationDeliveryAttemptId)
        assertNull(payload?.alertDispatchId)
        assertNull(payload?.incidentId)
        assertNull(payload?.title)
        assertNull(payload?.body)
    }

    @Test fun pendingAlertSurvivesCoordinatorRecreationAndCanBeClearedLater() {
        val store = FakePendingMonitorAlertStore()
        var now = 1234L
        val firstCoordinator = PendingMonitorAlertCoordinator(store, MonitorAlertClock { now })

        assertTrue(firstCoordinator.record(requireNotNull(MonitorPushPayloadParser.parse(validData()))))
        now = 9999L
        assertTrue(firstCoordinator.record(requireNotNull(MonitorPushPayloadParser.parse(validData()))))
        val restored = PendingMonitorAlertCoordinator(store).pending()

        assertEquals(ATTEMPT_ID, restored?.notificationDeliveryAttemptId)
        assertEquals(ALERT_DISPATCH_ID, restored?.alertDispatchId)
        assertEquals(INCIDENT_ID, restored?.incidentId)
        assertEquals(1234L, restored?.receivedAtEpochMillis)
        assertTrue(firstCoordinator.clear())
        assertNull(PendingMonitorAlertCoordinator(store).pending())
    }

    @Test fun sameDeliveryAttemptUsesStableNotificationIdInsteadOfCreatingDuplicates() {
        val first = MonitorAlertNotificationFactory.notificationId(ATTEMPT_ID)
        val repeated = MonitorAlertNotificationFactory.notificationId(ATTEMPT_ID)
        val another = MonitorAlertNotificationFactory.notificationId("different-attempt")

        assertEquals(first, repeated)
        assertTrue(first != another)
    }

    @Test fun foregroundHandlerStoresValidPayloadAndRespectsUnavailableNotifications() {
        val store = FakePendingMonitorAlertStore()
        var presentationCalls = 0
        val handler = MonitorAlertMessageHandler(
            coordinator = PendingMonitorAlertCoordinator(store),
            presenter = {
                presentationCalls++
                MonitorAlertNotificationResult.PermissionUnavailable
            }
        )

        val result = handler.handle(
            data = validData(),
            title = "MotoSOS Alert",
            body = "Emergency alert available in MotoSOS."
        )

        assertTrue(result.validPayload)
        assertTrue(result.stored)
        assertEquals(MonitorAlertNotificationResult.PermissionUnavailable, result.notificationResult)
        assertEquals(1, presentationCalls)
        assertEquals(ATTEMPT_ID, store.read()?.notificationDeliveryAttemptId)
    }

    @Test fun foregroundHandlerRejectsInvalidPayloadWithoutStorageOrNotification() {
        val store = FakePendingMonitorAlertStore()
        var presentationCalls = 0
        val handler = MonitorAlertMessageHandler(
            coordinator = PendingMonitorAlertCoordinator(store),
            presenter = {
                presentationCalls++
                MonitorAlertNotificationResult.Shown
            }
        )

        val result = handler.handle(emptyMap(), null, null)

        assertFalse(result.validPayload)
        assertFalse(result.stored)
        assertNull(result.notificationResult)
        assertEquals(0, presentationCalls)
        assertNull(store.read())
    }

    @Test fun pendingCaptureDoesNotChangeAuthenticationOrRoleRouting() {
        listOf(
            UserRole.Monitor to RootDestination.Monitor,
            UserRole.Rider to RootDestination.Rider
        ).forEach { (role, expectedDestination) ->
            val store = FakePendingMonitorAlertStore()
            val coordinator = PendingMonitorAlertCoordinator(store)

            coordinator.record(requireNotNull(MonitorPushPayloadParser.parse(validData())))

            assertEquals(
                expectedDestination,
                resolveRootDestination(
                    LoginUiState(
                        startupState = AuthEntryStatus.Authenticated,
                        authenticatedRole = role
                    )
                )
            )
            assertEquals(ATTEMPT_ID, coordinator.pending()?.notificationDeliveryAttemptId)
        }
    }

    @Test fun diagnosticsAndModelsDoNotPrintCompletePayloadOrBackendIds() {
        val payload = requireNotNull(MonitorPushPayloadParser.parse(validData()))
        val pending = PendingMonitorAlertCoordinator(
            FakePendingMonitorAlertStore(),
            MonitorAlertClock { 1234L }
        ).let { coordinator ->
            coordinator.record(payload)
            coordinator.pending()
        }
        val diagnostic = PushDiagnostics.monitorAlertHandled(
            validPayload = true,
            stored = true,
            notificationResult = MonitorAlertNotificationResult.Shown
        )

        listOf(payload.toString(), pending.toString(), diagnostic).forEach { text ->
            assertFalse(text.contains(ATTEMPT_ID))
            assertFalse(text.contains(ALERT_DISPATCH_ID))
            assertFalse(text.contains(INCIDENT_ID))
        }
    }

    private fun validData(): Map<String, String> = mapOf(
        MonitorPushPayloadParser.KEY_NOTIFICATION_DELIVERY_ATTEMPT_ID to ATTEMPT_ID,
        MonitorPushPayloadParser.KEY_ALERT_DISPATCH_ID to ALERT_DISPATCH_ID,
        MonitorPushPayloadParser.KEY_INCIDENT_ID to INCIDENT_ID,
        MonitorPushPayloadParser.KEY_CHANNEL to "Push"
    )

    private companion object {
        const val ATTEMPT_ID = "attempt-id-test-only"
        const val ALERT_DISPATCH_ID = "alert-dispatch-id-test-only"
        const val INCIDENT_ID = "incident-id-test-only"
    }
}

private class FakePendingMonitorAlertStore : PendingMonitorAlertStore {
    private var state: PendingMonitorAlert? = null

    override fun read(): PendingMonitorAlert? = state

    override fun save(alert: PendingMonitorAlert): Boolean {
        state = alert
        return true
    }

    override fun clear(): Boolean {
        state = null
        return true
    }
}
