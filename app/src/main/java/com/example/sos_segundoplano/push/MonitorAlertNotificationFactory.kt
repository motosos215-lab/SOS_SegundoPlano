package com.example.sos_segundoplano.push

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.sos_segundoplano.MainActivity
import com.example.sos_segundoplano.R
import com.example.sos_segundoplano.domain.push.MonitorPushPayload
import com.example.sos_segundoplano.domain.push.MonitorPushPayloadParser

enum class MonitorAlertNotificationResult {
    Shown,
    PermissionUnavailable,
    PresentationMissing
}

class MonitorAlertNotificationFactory(
    context: Context,
    private val channelId: String = CHANNEL_ID
) {
    private val appContext = context.applicationContext
    private val notificationManager = appContext.getSystemService(NotificationManager::class.java)

    fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            channelId,
            appContext.getString(R.string.monitor_alert_notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = appContext.getString(R.string.monitor_alert_notification_channel_description)
            setShowBadge(true)
        }
        notificationManager.createNotificationChannel(channel)
    }

    fun show(payload: MonitorPushPayload): MonitorAlertNotificationResult {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return MonitorAlertNotificationResult.PermissionUnavailable
        }
        if (!notificationManager.areNotificationsEnabled()) {
            return MonitorAlertNotificationResult.PermissionUnavailable
        }
        val notification = build(payload) ?: return MonitorAlertNotificationResult.PresentationMissing
        notificationManager.notify(notificationId(payload.notificationDeliveryAttemptId), notification)
        return MonitorAlertNotificationResult.Shown
    }

    fun build(payload: MonitorPushPayload): Notification? {
        val title = payload.title?.takeIf { it.isNotBlank() } ?: return null
        val body = payload.body?.takeIf { it.isNotBlank() } ?: return null
        return NotificationCompat.Builder(appContext, channelId)
            .setSmallIcon(R.drawable.ic_motosos_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(createContentIntent(payload))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
    }

    private fun createContentIntent(payload: MonitorPushPayload): PendingIntent {
        val notificationId = notificationId(payload.notificationDeliveryAttemptId)
        return PendingIntent.getActivity(
            appContext,
            notificationId,
            MonitorAlertIntent.create(appContext, payload),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val CHANNEL_ID = "motosos_monitor_alerts"
        private const val NOTIFICATION_ID_BASE = 24000

        fun notificationId(notificationDeliveryAttemptId: String): Int =
            NOTIFICATION_ID_BASE + (notificationDeliveryAttemptId.hashCode() and 0x000fffff)
    }
}

object MonitorAlertIntent {
    private const val EXTRA_TITLE = "monitorPushTitle"
    private const val EXTRA_BODY = "monitorPushBody"

    fun create(context: Context, payload: MonitorPushPayload): Intent =
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(
                MonitorPushPayloadParser.KEY_NOTIFICATION_DELIVERY_ATTEMPT_ID,
                payload.notificationDeliveryAttemptId
            )
            payload.alertDispatchId?.let {
                putExtra(MonitorPushPayloadParser.KEY_ALERT_DISPATCH_ID, it)
            }
            payload.incidentId?.let {
                putExtra(MonitorPushPayloadParser.KEY_INCIDENT_ID, it)
            }
            putExtra(MonitorPushPayloadParser.KEY_CHANNEL, payload.channel)
            payload.title?.let { putExtra(EXTRA_TITLE, it) }
            payload.body?.let { putExtra(EXTRA_BODY, it) }
        }

    fun parse(intent: Intent?): MonitorPushPayload? {
        if (intent == null) return null
        val data = buildMap {
            listOf(
                MonitorPushPayloadParser.KEY_NOTIFICATION_DELIVERY_ATTEMPT_ID,
                MonitorPushPayloadParser.KEY_ALERT_DISPATCH_ID,
                MonitorPushPayloadParser.KEY_INCIDENT_ID,
                MonitorPushPayloadParser.KEY_CHANNEL
            ).forEach { key -> intent.getStringExtra(key)?.let { put(key, it) } }
        }
        return MonitorPushPayloadParser.parse(
            data = data,
            title = intent.getStringExtra(EXTRA_TITLE),
            body = intent.getStringExtra(EXTRA_BODY)
        )
    }
}
