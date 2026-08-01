package com.example.sos_segundoplano.features.background

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.example.sos_segundoplano.data.signals.TripSignalCaptureCoordinator

class MonitoringForegroundService : Service() {
    private val notificationFactory: MonitoringNotificationFactory by lazy {
        MonitoringNotificationFactory(this)
    }
    private val captureCoordinator: TripSignalCaptureCoordinator by lazy {
        TripSignalCaptureCoordinator(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START_MONITORING) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        notificationFactory.createChannel()
        if (!notificationFactory.isChannelEnabled()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        return try {
            promoteToForeground(notificationFactory.buildNotification())
            captureCoordinator.start()
            START_NOT_STICKY
        } catch (_: SecurityException) {
            stopSelf(startId)
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        captureCoordinator.stop()
        super.onDestroy()
    }

    private fun promoteToForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                MonitoringNotificationFactory.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(
                MonitoringNotificationFactory.NOTIFICATION_ID,
                notification
            )
        }
    }

    companion object {
        const val ACTION_START_MONITORING = "com.example.sos_segundoplano.action.START_MONITORING"

        fun createStartIntent(context: Context): Intent = Intent(context, MonitoringForegroundService::class.java)
            .setAction(ACTION_START_MONITORING)
    }
}
