package com.example.sos_segundoplano.data.remote.trip

import android.content.Context
import android.util.Log
import com.example.sos_segundoplano.BuildConfig
import com.example.sos_segundoplano.core.auth.AuthProvider
import com.example.sos_segundoplano.data.remote.auth.AuthNetworkFactory

class TripRemoteSessionDependencies(
    val store: RemoteTripSessionStore,
    val reconciler: TripRemoteSessionReconciler,
    val starter: RemoteTripStarter,
    val resolvedStarter: ResolvedRemoteTripStarter,
    val finisher: RemoteTripFinisher
)

object TripRemoteSessionProvider {
    @Volatile private var dependencies: TripRemoteSessionDependencies? = null

    fun initialize(context: Context): TripRemoteSessionDependencies = get(context)

    fun get(context: Context): TripRemoteSessionDependencies = dependencies ?: synchronized(this) {
        dependencies ?: create(context.applicationContext).also { dependencies = it }
    }

    private fun create(context: Context): TripRemoteSessionDependencies {
        val moshi = AuthNetworkFactory.createMoshi()
        val api = AuthNetworkFactory.createTripsApi(BuildConfig.MOTOSOS_API_BASE_URL, moshi)
        val store = PersistentRemoteTripSessionStore(
            SharedPreferencesRemoteTripSessionPersistence(context)
        )
        val authRepository = AuthProvider.get(context)
        val remoteDataSource = RetrofitTripRemoteDataSource(api, moshi)
        val starter = AuthenticatedRemoteTripStarter(authRepository, remoteDataSource, store)
        return TripRemoteSessionDependencies(
            store = store,
            reconciler = TripRemoteSessionReconciler(
                authRepository = authRepository,
                remoteDataSource = remoteDataSource,
                store = store,
                logger = AndroidTripRemoteSessionLogger
            ),
            starter = starter,
            resolvedStarter = DefaultResolvedRemoteTripStarter(
                resourcesResolver = AuthenticatedTripStartResourcesResolver(authRepository, remoteDataSource),
                remoteTripStarter = starter
            ),
            finisher = AuthenticatedRemoteTripFinisher(authRepository, remoteDataSource, store)
        )
    }
}

private object AndroidTripRemoteSessionLogger : TripRemoteSessionLogger {
    override fun activeRemoteTripFound() {
        Log.d(TAG, "active remote trip found")
    }

    override fun remoteTripIdAvailable() {
        Log.d(TAG, "remoteTripId available")
    }

    override fun noActiveRemoteTrip() {
        Log.w(TAG, "no active remote trip")
    }

    override fun tripLookupFailed() {
        Log.w(TAG, "trip lookup failed")
    }

    override fun tripPersistenceFailed() {
        Log.w(TAG, "remote trip persistence failed")
    }

    private const val TAG = "MotoSOS.RemoteTrip"
}
