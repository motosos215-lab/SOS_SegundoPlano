package com.example.sos_segundoplano.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.sos_segundoplano.MainActivity
import com.example.sos_segundoplano.R
import com.example.sos_segundoplano.domain.push.RiderMonitorFeedbackPayload
import com.example.sos_segundoplano.domain.push.RiderMonitorFeedbackType

class RiderMonitorFeedbackNotificationFactory(
    context: Context,
    private val channelId: String = CHANNEL_ID
) {
    private val appContext = context.applicationContext
    private val notificationManager = appContext.getSystemService(NotificationManager::class.java)

    fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                channelId,
                appContext.getString(R.string.rider_feedback_notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = appContext.getString(R.string.rider_feedback_notification_channel_description)
                setShowBadge(true)
            }
        )
    }

    fun show(payload: RiderMonitorFeedbackPayload): MonitorAlertNotificationResult {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return MonitorAlertNotificationResult.PermissionUnavailable
        if (!notificationManager.areNotificationsEnabled()) return MonitorAlertNotificationResult.PermissionUnavailable

        val body = payload.body?.takeIf { it.isNotBlank() } ?: payload.type.defaultMessage()
        val notification = NotificationCompat.Builder(appContext, channelId)
            .setSmallIcon(R.drawable.ic_motosos_notification)
            .setContentTitle(appContext.getString(R.string.rider_feedback_notification_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(createContentIntent(payload))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()

        notificationManager.notify(notificationId(payload.notificationDeliveryAttemptId), notification)
        return MonitorAlertNotificationResult.Shown
    }

    private fun createContentIntent(payload: RiderMonitorFeedbackPayload): PendingIntent =
        PendingIntent.getActivity(
            appContext,
            notificationId(payload.notificationDeliveryAttemptId),
            Intent().apply {
                component = ComponentName(appContext, MainActivity::class.java)
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_OPEN_RIDER_MESSAGES, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    companion object {
        const val CHANNEL_ID = "motosos_rider_monitor_feedback"
        const val EXTRA_OPEN_RIDER_MESSAGES = "openRiderMonitorMessages"
        private const val NOTIFICATION_ID_BASE = 42000

        fun notificationId(notificationDeliveryAttemptId: String): Int =
            NOTIFICATION_ID_BASE + (notificationDeliveryAttemptId.hashCode() and 0x000fffff)
    }
}

internal fun RiderMonitorFeedbackType.defaultMessage(): String = when (this) {
    RiderMonitorFeedbackType.Viewed -> "Tu contacto de emergencia vio tu alerta SOS."
    RiderMonitorFeedbackType.Acknowledged -> "Tu contacto de emergencia confirmó que recibió tu alerta SOS."
    RiderMonitorFeedbackType.Declined -> "Tu contacto de emergencia indicó que no puede atender la alerta en este momento."
}
