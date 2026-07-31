package com.example.sos_segundoplano.features.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.sos_segundoplano.MainActivity
import com.example.sos_segundoplano.R

class MonitoringNotificationFactory(
    context: Context,
    private val channelId: String = CHANNEL_ID,
    val notificationId: Int = NOTIFICATION_ID
) {
    private val appContext = context.applicationContext
    private val notificationManager = appContext.getSystemService(NotificationManager::class.java)

    fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val channel = NotificationChannel(
            channelId,
            appContext.getString(R.string.monitoring_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = appContext.getString(R.string.monitoring_notification_channel_description)
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    fun isChannelEnabled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return true
        }

        val channel = notificationManager.getNotificationChannel(channelId)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun buildNotification(): Notification = NotificationCompat.Builder(appContext, channelId)
        .setSmallIcon(R.drawable.ic_motosos_notification)
        .setContentTitle(appContext.getString(R.string.monitoring_notification_title))
        .setContentText(appContext.getString(R.string.monitoring_notification_content))
        .setContentIntent(createContentIntent())
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .build()

    private fun createContentIntent(): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            appContext,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val CHANNEL_ID = "active_trip_monitoring"
        const val NOTIFICATION_ID = 13016
    }
}
