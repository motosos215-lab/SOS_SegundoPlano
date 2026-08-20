package com.example.sos_segundoplano

import android.app.Application
import com.example.sos_segundoplano.core.auth.AuthProvider
import com.example.sos_segundoplano.core.push.MonitorAlertProvider
import com.example.sos_segundoplano.core.push.PushTokenProvider
import com.example.sos_segundoplano.core.push.RiderMonitorFeedbackProvider
import com.example.sos_segundoplano.data.offline.OfflineQueueProvider
import com.example.sos_segundoplano.data.remote.incident.IncidentRemoteProvider
import com.example.sos_segundoplano.data.remote.trip.TripRemoteSessionProvider
import com.example.sos_segundoplano.data.route.TripRouteProvider
import com.example.sos_segundoplano.data.trip.TripTimingStoreProvider
import com.example.sos_segundoplano.data.trip.TripSessionStoreProvider
import com.example.sos_segundoplano.domain.auth.SessionState
import com.example.sos_segundoplano.domain.auth.UserRole
import com.example.sos_segundoplano.push.MonitorAlertNotificationFactory
import com.example.sos_segundoplano.push.RiderMonitorFeedbackNotificationFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MotoSosApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val authRepository = AuthProvider.initialize(applicationContext)
        MonitorAlertProvider.initialize(applicationContext)
        MonitorAlertNotificationFactory(applicationContext).createChannel()
        RiderMonitorFeedbackProvider.initialize(applicationContext)
        RiderMonitorFeedbackNotificationFactory(applicationContext).createChannel()
        PushTokenProvider.initialize(applicationContext)
        TripTimingStoreProvider.initialize(applicationContext)
        TripSessionStoreProvider.initialize(applicationContext)
        val tripRemoteDependencies = TripRemoteSessionProvider.initialize(applicationContext)
        IncidentRemoteProvider.initialize(applicationContext)
        OfflineQueueProvider.initialize(applicationContext)
        val routeDependencies = TripRouteProvider.initialize(applicationContext)

        // Pending route points survive process death and trip finish. Schedule their sync when a
        // Rider session actually becomes available, avoiding the startup race with encrypted
        // session restoration and also covering a fresh login later in the same process.
        applicationScope.launch {
            authRepository.observeSession()
                .map { state ->
                    when (state) {
                        is SessionState.Authenticated -> state.user.takeIf { it.role == UserRole.Rider }?.id
                        is SessionState.Refreshing -> state.user.takeIf { it.role == UserRole.Rider }?.id
                        else -> null
                    }
                }
                .distinctUntilChanged()
                .collect { riderId ->
                    if (!riderId.isNullOrBlank()) {
                        routeDependencies.scheduler.schedule()
                        if (tripRemoteDependencies.pendingFinishStore.readForOwner(riderId) != null) {
                            tripRemoteDependencies.pendingFinishStore.expedite(riderId, System.currentTimeMillis())
                            tripRemoteDependencies.finishRecoveryScheduler.scheduleImmediate()
                        }
                    }
                }
        }
    }
}
