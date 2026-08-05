package com.example.sos_segundoplano

import android.app.Application
import com.example.sos_segundoplano.core.auth.AuthProvider
import com.example.sos_segundoplano.data.offline.OfflineQueueProvider

class MotoSosApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AuthProvider.initialize(applicationContext)
        OfflineQueueProvider.initialize(applicationContext)
    }
}
