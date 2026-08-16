package com.example.sos_segundoplano.data.wear

import android.content.Context
import com.example.sos_segundoplano.core.background.AndroidMonitoringServiceStarter
import com.example.sos_segundoplano.core.background.AndroidMonitoringServiceStopper
import com.example.sos_segundoplano.core.auth.AuthProvider
import com.example.sos_segundoplano.core.permissions.AppNotificationStatusChecker
import com.example.sos_segundoplano.core.permissions.BackgroundLocationPermissionChecker
import com.example.sos_segundoplano.core.permissions.BluetoothRequirementChecker
import com.example.sos_segundoplano.data.remote.trip.TripRemoteSessionProvider
import com.example.sos_segundoplano.data.remote.incident.IncidentRemoteProvider
import com.example.sos_segundoplano.data.trip.TripSessionStoreProvider
import com.example.sos_segundoplano.data.signals.TripSignalStoreProvider
import java.time.Instant

object WearPhoneActionProvider {
    @Volatile private var coordinator: WearPhoneActionCoordinator? = null
    fun get(context: Context): WearPhoneActionCoordinator = coordinator ?: synchronized(this) {
        coordinator ?: create(context.applicationContext).also { coordinator = it }
    }

    private fun create(context: Context): WearPhoneActionCoordinator {
        val remote = TripRemoteSessionProvider.get(context)
        return WearPhoneActionCoordinator(
            authRepository = AuthProvider.get(context),
            remoteTripStore = remote.store,
            startDependencies = WearStartTripDependencies(
                resolvedStarter = remote.resolvedStarter,
                monitoringServiceStarter = AndroidMonitoringServiceStarter(context),
                tripSessionStore = TripSessionStoreProvider.store,
                locationStatusProvider = BackgroundLocationPermissionChecker(context),
                notificationStatusProvider = AppNotificationStatusChecker(context),
                bluetoothStatusProvider = BluetoothRequirementChecker(context),
                commandStore = SharedPreferencesWearCommandResultStore(context),
                now = System::currentTimeMillis,
            ),
            finishDependencies = WearFinishTripDependencies(
                finisher = remote.finisher,
                monitoringServiceStopper = AndroidMonitoringServiceStopper(context),
                tripSessionStore = TripSessionStoreProvider.store,
                commandStore = SharedPreferencesWearCommandResultStore(context),
                finishRequestFactory = {
                    val location = TripSignalStoreProvider.store.snapshots.value.location
                    buildWearFinishRequest(Instant.now().toString(), location.availability, location.sample)
                },
                now = System::currentTimeMillis,
            ),
            manualSosDependencies = WearManualSosDependencies(
                manualSosRequester = { IncidentRemoteProvider.requestManualSosAwait(context) },
                commandStore = SharedPreferencesWearCommandResultStore(context),
                now = System::currentTimeMillis,
            ),
        )
    }
}
