package com.example.sos_segundoplano.data.route

import android.content.Context
import com.example.sos_segundoplano.BuildConfig
import com.example.sos_segundoplano.core.auth.AuthProvider
import com.example.sos_segundoplano.data.remote.auth.AuthNetworkFactory
import com.example.sos_segundoplano.data.remote.trip.TripsApi
import com.example.sos_segundoplano.data.remote.trip.TripRemoteSessionProvider
import com.example.sos_segundoplano.data.signals.TripSignalStoreProvider
import com.example.sos_segundoplano.data.trip.TripSessionStoreProvider

class TripRouteDependencies(
    val database: TripRouteDatabase,
    val api: TripsApi,
    val scheduler: TripRouteWorkScheduler,
    val recorder: TripRouteRecorder
)

object TripRouteProvider {
    @Volatile private var dependencies: TripRouteDependencies? = null

    fun initialize(context: Context): TripRouteDependencies = get(context)

    fun get(context: Context): TripRouteDependencies = dependencies ?: synchronized(this) {
        dependencies ?: create(context.applicationContext).also { dependencies = it }
    }

    private fun create(context: Context): TripRouteDependencies {
        val database = TripRouteDatabase.create(context)
        val scheduler = TripRouteWorkScheduler(context)
        val api = AuthNetworkFactory.createTripsApi(BuildConfig.MOTOSOS_API_BASE_URL)
        return TripRouteDependencies(
            database = database,
            api = api,
            scheduler = scheduler,
            recorder = TripRouteRecorder(
                authRepository = AuthProvider.get(context),
                signalStore = TripSignalStoreProvider.store,
                tripSessionStore = TripSessionStoreProvider.store,
                remoteTripSessionStore = TripRemoteSessionProvider.get(context).store,
                dao = database.routePointDao(),
                scheduler = scheduler
            )
        )
    }
}
