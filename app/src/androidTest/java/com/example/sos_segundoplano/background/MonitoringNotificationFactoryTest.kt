package com.example.sos_segundoplano.background

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.example.sos_segundoplano.R
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
    private val factory = MonitoringNotificationFactory(
        context = context,
        channelId = testChannelId,
        notificationId = 13017
    )

    @After
    fun tearDown() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.deleteNotificationChannel(testChannelId)
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
    fun notificationIdIsPositive() {
        assertTrue(factory.notificationId > 0)
        assertTrue(MonitoringNotificationFactory.NOTIFICATION_ID > 0)
    }
}
