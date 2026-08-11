package com.example.sos_segundoplano

import android.app.Application
import com.example.sos_segundoplano.core.auth.AuthProvider
import com.example.sos_segundoplano.core.push.MonitorAlertProvider
import com.example.sos_segundoplano.core.push.PushTokenProvider
import com.example.sos_segundoplano.data.offline.OfflineQueueProvider
import com.example.sos_segundoplano.data.remote.incident.IncidentRemoteProvider
import com.example.sos_segundoplano.data.remote.trip.TripRemoteSessionProvider
import com.example.sos_segundoplano.data.trip.TripTimingStoreProvider
import com.example.sos_segundoplano.push.MonitorAlertNotificationFactory

class MotoSosApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AuthProvider.initialize(applicationContext)
        MonitorAlertProvider.initialize(applicationContext)
        MonitorAlertNotificationFactory(applicationContext).createChannel()
        PushTokenProvider.initialize(applicationContext)
        TripTimingStoreProvider.initialize(applicationContext)
        TripRemoteSessionProvider.initialize(applicationContext)
        IncidentRemoteProvider.initialize(applicationContext)
        OfflineQueueProvider.initialize(applicationContext)
    }
}
