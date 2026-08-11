package com.example.sos_segundoplano.core.push

import android.content.Context
import com.example.sos_segundoplano.data.local.push.SharedPreferencesPendingMonitorAlertStore
import com.example.sos_segundoplano.domain.push.PendingMonitorAlertCoordinator

object MonitorAlertProvider {
    @Volatile
    private var coordinator: PendingMonitorAlertCoordinator? = null

    fun initialize(context: Context): PendingMonitorAlertCoordinator = get(context)

    fun get(context: Context): PendingMonitorAlertCoordinator = coordinator ?: synchronized(this) {
        coordinator ?: PendingMonitorAlertCoordinator(
            SharedPreferencesPendingMonitorAlertStore(context.applicationContext)
        ).also { coordinator = it }
    }
}
