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
import com.example.sos_segundoplano.domain.validation.FalsePositiveValidationState
import com.example.sos_segundoplano.domain.validation.IncidentCause

class MonitoringNotificationFactory(
    context: Context,
    private val channelId: String = CHANNEL_ID,
    private val emergencyChannelId: String = EMERGENCY_CHANNEL_ID,
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

        val emergencyChannel = NotificationChannel(
            emergencyChannelId,
            appContext.getString(R.string.emergency_notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = appContext.getString(R.string.emergency_notification_channel_description)
            setShowBadge(true)
            enableLights(true)
            enableVibration(true)
        }
        notificationManager.createNotificationChannel(emergencyChannel)
    }

    fun isChannelEnabled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return true
        }

        val channel = notificationManager.getNotificationChannel(channelId)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun buildNotification(
        validationState: FalsePositiveValidationState = FalsePositiveValidationState.Idle
    ): Notification = if (validationState.isEmergencyNotificationState()) {
        buildEmergencyNotification(validationState)
    } else {
        buildMonitoringNotification()
    }

    fun buildMonitoringNotification(): Notification = NotificationCompat.Builder(appContext, channelId)
        .setSmallIcon(R.drawable.ic_motosos_notification)
        .setContentTitle(appContext.getString(R.string.monitoring_notification_title))
        .setContentText(appContext.getString(R.string.monitoring_notification_content))
        .setContentIntent(createContentIntent(0))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .build()

    fun buildEmergencyNotification(
        validationState: FalsePositiveValidationState
    ): Notification {
        val countdown = validationState as? FalsePositiveValidationState.CountdownActive
        val builder = NotificationCompat.Builder(appContext, emergencyChannelId)
        .setSmallIcon(R.drawable.ic_motosos_notification)
        .setContentTitle(notificationTitle(validationState))
        .setContentText(notificationContent(validationState))
        .setContentIntent(createContentIntent(EMERGENCY_NOTIFICATION_ID))
        .setOngoing(false)
        .setOnlyAlertOnce(true)
        .setAutoCancel(false)
        .setCategory(NotificationCompat.CATEGORY_ALARM)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)

        if (countdown != null) {
            builder.addAction(
                0,
                appContext.getString(R.string.validation_confirm_safe),
                createServiceActionIntent(MonitoringForegroundService.ACTION_CONFIRM_SAFE, countdown, 1)
            )
            builder.addAction(
                0,
                appContext.getString(R.string.validation_request_help),
                createServiceActionIntent(MonitoringForegroundService.ACTION_REQUEST_HELP, countdown, 2)
            )
        }
        return builder.build()
    }

    private fun createContentIntent(requestCode: Int): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            appContext,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createServiceActionIntent(action: String, state: FalsePositiveValidationState.CountdownActive, requestCodeOffset: Int): PendingIntent {
        val requestCode = ((state.metadata.assessmentId and Int.MAX_VALUE.toLong()).toInt() * 10) + requestCodeOffset
        val intent = Intent(appContext, MonitoringForegroundService::class.java)
            .setAction(action)
            .putExtra(MonitoringForegroundService.EXTRA_SESSION_ID, state.metadata.sessionId)
            .putExtra(MonitoringForegroundService.EXTRA_ASSESSMENT_ID, state.metadata.assessmentId)
            .putExtra(MonitoringForegroundService.EXTRA_RESPONSE_ID, "notification-${state.metadata.sessionId}-${state.metadata.assessmentId}-$requestCodeOffset")
        return PendingIntent.getService(
            appContext,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun notificationTitle(state: FalsePositiveValidationState): String = when (state) {
        is FalsePositiveValidationState.CountdownActive -> appContext.getString(R.string.validation_countdown_title)
        is FalsePositiveValidationState.HelpRequested -> appContext.getString(R.string.validation_request_help)
        is FalsePositiveValidationState.ImmediateAlertRequested -> appContext.getString(R.string.validation_immediate_alert_requested_title)
        is FalsePositiveValidationState.IncidentGenerated -> appContext.getString(R.string.validation_incident_generated)
        is FalsePositiveValidationState.Error -> appContext.getString(R.string.validation_local_registration_failed_title)
        else -> appContext.getString(R.string.monitoring_notification_title)
    }

    private fun notificationContent(state: FalsePositiveValidationState): String = when (state) {
        is FalsePositiveValidationState.CountdownActive -> {
            val remainingSeconds = ((state.remainingNanos + NANOS_PER_SECOND - 1L) / NANOS_PER_SECOND).coerceAtLeast(0L)
            appContext.getString(R.string.validation_countdown_seconds, remainingSeconds)
        }
        is FalsePositiveValidationState.IncidentGenerated -> when (state.incident.cause) {
            IncidentCause.UserRequestedHelp -> appContext.getString(R.string.validation_help_requested)
            IncidentCause.Timeout -> appContext.getString(R.string.validation_timeout_escalated)
            IncidentCause.CriticalPhysicalEvent -> appContext.getString(R.string.validation_immediate_alert_requested)
        }
        is FalsePositiveValidationState.HelpRequested -> appContext.getString(R.string.validation_help_requested)
        is FalsePositiveValidationState.ImmediateAlertRequested -> appContext.getString(R.string.validation_immediate_alert_requested)
        is FalsePositiveValidationState.Error -> appContext.getString(R.string.validation_local_registration_failed)
        else -> appContext.getString(R.string.monitoring_notification_content)
    }

    companion object {
        const val CHANNEL_ID = "active_trip_monitoring"
        const val EMERGENCY_CHANNEL_ID = "possible_accident_alerts"
        const val NOTIFICATION_ID = 13016
        const val EMERGENCY_NOTIFICATION_ID = 13018
        private const val NANOS_PER_SECOND = 1_000_000_000L
    }
}

private fun FalsePositiveValidationState.isEmergencyNotificationState(): Boolean = when (this) {
    is FalsePositiveValidationState.CountdownActive,
    is FalsePositiveValidationState.HelpRequested,
    is FalsePositiveValidationState.IncidentGenerated,
    is FalsePositiveValidationState.ImmediateAlertRequested,
    is FalsePositiveValidationState.Error -> true
    else -> false
}
