package com.example.sos_segundoplano

import android.app.Application
import com.example.sos_segundoplano.core.auth.AuthProvider
import com.example.sos_segundoplano.data.offline.OfflineQueueProvider
import com.example.sos_segundoplano.data.remote.incident.IncidentRemoteProvider

class MotoSosApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AuthProvider.initialize(applicationContext)
        IncidentRemoteProvider.initialize(applicationContext)
        OfflineQueueProvider.initialize(applicationContext)
    }
}
