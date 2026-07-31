package com.example.sos_segundoplano.core.background

import android.content.Context
import androidx.core.content.ContextCompat
import com.example.sos_segundoplano.features.background.MonitoringForegroundService
import com.example.sos_segundoplano.features.background.MonitoringNotificationFactory

fun interface MonitoringServiceStarter {
    fun start(): MonitoringServiceStartResult
}

class AndroidMonitoringServiceStarter(
    context: Context,
    private val notificationFactory: MonitoringNotificationFactory = MonitoringNotificationFactory(context)
) : MonitoringServiceStarter {
    private val appContext = context.applicationContext

    override fun start(): MonitoringServiceStartResult {
        notificationFactory.createChannel()
        if (!notificationFactory.isChannelEnabled()) {
            return MonitoringServiceStartResult.Failed
        }

        val intent = MonitoringForegroundService.createStartIntent(appContext)
        return try {
            ContextCompat.startForegroundService(appContext, intent)
            MonitoringServiceStartResult.Started
        } catch (_: SecurityException) {
            MonitoringServiceStartResult.Failed
        } catch (_: IllegalStateException) {
            MonitoringServiceStartResult.Failed
        }
    }
}
