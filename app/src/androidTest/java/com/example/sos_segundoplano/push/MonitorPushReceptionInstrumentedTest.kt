package com.example.sos_segundoplano.push

import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.sos_segundoplano.MainActivity
import com.example.sos_segundoplano.data.local.push.SharedPreferencesPendingMonitorAlertStore
import com.example.sos_segundoplano.domain.push.MonitorPushPayload
import com.example.sos_segundoplano.domain.push.PendingMonitorAlert
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MonitorPushReceptionInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val store = SharedPreferencesPendingMonitorAlertStore(context, TEST_PREFERENCES)

    @Before fun setUp() {
        store.clear()
    }

    @After fun tearDown() {
        store.clear()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java)
                .deleteNotificationChannel(TEST_CHANNEL_ID)
        }
    }

    @Test fun pendingAlertPersistsAcrossStoreInstancesAndCanBeCleared() {
        assertTrue(store.save(pendingAlert()))

        val restored = SharedPreferencesPendingMonitorAlertStore(context, TEST_PREFERENCES).read()

        assertEquals(ATTEMPT_ID, restored?.notificationDeliveryAttemptId)
        assertEquals(ALERT_DISPATCH_ID, restored?.alertDispatchId)
        assertEquals(INCIDENT_ID, restored?.incidentId)
        assertTrue(store.clear())
        assertNull(SharedPreferencesPendingMonitorAlertStore(context, TEST_PREFERENCES).read())
    }

    @Test fun notificationTapIntentTargetsMainActivityAndRoundTripsConfirmedPayload() {
        val intent = MonitorAlertIntent.create(context, payload())
        val restored = MonitorAlertIntent.parse(intent)

        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertEquals(ATTEMPT_ID, restored?.notificationDeliveryAttemptId)
        assertEquals(ALERT_DISPATCH_ID, restored?.alertDispatchId)
        assertEquals(INCIDENT_ID, restored?.incidentId)
        assertEquals("Push", restored?.channel)
        assertFalse(intent.hasExtra("route"))
        assertFalse(intent.hasExtra("deepLink"))
        assertFalse(intent.hasExtra("role"))
    }

    @Test fun dedicatedChannelAndForegroundNotificationUseStableNonMonitoringChannel() {
        val factory = MonitorAlertNotificationFactory(context, TEST_CHANNEL_ID)
        factory.createChannel()
        val notification = factory.build(payload())

        assertNotNull(notification)
        assertEquals(TEST_CHANNEL_ID, notification?.channelId)
        assertTrue(MonitorAlertNotificationFactory.CHANNEL_ID != "active_trip_monitoring")
        assertNotNull(notification?.contentIntent)
    }

    @Test fun incompletePresentationAndDisabledPermissionPathDoNotCrash() {
        val factory = MonitorAlertNotificationFactory(context)
        val withoutPresentation = payload().copy(title = null, body = null)

        assertNull(factory.build(withoutPresentation))
        val result = factory.show(withoutPresentation)
        assertTrue(
            result == MonitorAlertNotificationResult.PresentationMissing ||
                result == MonitorAlertNotificationResult.PermissionUnavailable
        )
    }

    private fun payload() = MonitorPushPayload(
        notificationDeliveryAttemptId = ATTEMPT_ID,
        alertDispatchId = ALERT_DISPATCH_ID,
        incidentId = INCIDENT_ID,
        channel = "Push",
        title = "MotoSOS Alert",
        body = "Emergency alert available in MotoSOS."
    )

    private fun pendingAlert() = PendingMonitorAlert(
        notificationDeliveryAttemptId = ATTEMPT_ID,
        alertDispatchId = ALERT_DISPATCH_ID,
        incidentId = INCIDENT_ID,
        receivedAtEpochMillis = 1234L
    )

    private companion object {
        const val TEST_PREFERENCES = "motosos_pending_monitor_alert_test"
        const val TEST_CHANNEL_ID = "motosos_monitor_alerts_test"
        const val ATTEMPT_ID = "attempt-id-test-only"
        const val ALERT_DISPATCH_ID = "alert-dispatch-id-test-only"
        const val INCIDENT_ID = "incident-id-test-only"
    }
}
