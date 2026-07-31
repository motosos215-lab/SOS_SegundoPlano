package com.example.sos_segundoplano.core.background

import android.content.Context
import android.content.Intent
import com.example.sos_segundoplano.features.background.MonitoringForegroundService

fun interface MonitoringServiceStopper {
    fun stop(): MonitoringServiceStopResult
}

class AndroidMonitoringServiceStopper(
    context: Context
) : MonitoringServiceStopper {
    private val appContext = context.applicationContext

    override fun stop(): MonitoringServiceStopResult {
        val intent = Intent(appContext, MonitoringForegroundService::class.java)
        return try {
            if (appContext.stopService(intent)) {
                MonitoringServiceStopResult.Stopped
            } else {
                MonitoringServiceStopResult.AlreadyStopped
            }
        } catch (_: SecurityException) {
            MonitoringServiceStopResult.Failed
        }
    }
}
