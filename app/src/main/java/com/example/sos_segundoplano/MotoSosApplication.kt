package com.example.sos_segundoplano

import android.app.Application
import com.example.sos_segundoplano.data.offline.OfflineQueueProvider

class MotoSosApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        OfflineQueueProvider.initialize(applicationContext)
    }
}
