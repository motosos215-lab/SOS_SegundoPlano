package com.example.sos_segundoplano.data.remote.incident

import android.content.Context
import android.util.Log
import com.example.sos_segundoplano.BuildConfig
import com.example.sos_segundoplano.core.auth.AuthProvider
import com.example.sos_segundoplano.data.remote.auth.AuthNetworkFactory
import com.example.sos_segundoplano.data.remote.trip.TripRemoteSessionProvider
import com.example.sos_segundoplano.data.validation.FalsePositiveValidationCoordinatorProvider

object IncidentRemoteProvider {
    @Volatile private var creator: IncidentRemoteCreator? = null

    fun initialize(context: Context): IncidentRemoteCreator = get(context).also { remoteCreator ->
        FalsePositiveValidationCoordinatorProvider.setIncidentRemoteCreator(remoteCreator)
    }

    fun get(context: Context): IncidentRemoteCreator = creator ?: synchronized(this) {
        creator ?: create(context.applicationContext).also { creator = it }
    }

    private fun create(context: Context): IncidentRemoteCreator {
        val moshi = AuthNetworkFactory.createMoshi()
        val api = AuthNetworkFactory.createIncidentsApi(BuildConfig.MOTOSOS_API_BASE_URL, moshi)
        val tripSession = TripRemoteSessionProvider.get(context)
        return AuthenticatedIncidentRemoteCreator(
            authRepository = AuthProvider.get(context),
            remoteDataSource = RetrofitIncidentRemoteDataSource(api, moshi),
            activeTripRemoteResolver = tripSession.reconciler,
            logger = AndroidIncidentRemoteLogger
        )
    }
}

private object AndroidIncidentRemoteLogger : IncidentRemoteLogger {
    override fun remoteIncidentRequestStarted() {
        Log.d(TAG, "remote incident request started")
    }

    override fun remoteIncidentCreated() {
        Log.d(TAG, "remote incident created")
    }

    override fun remoteIncidentIdReceived() {
        Log.d(TAG, "remoteIncidentId received")
    }

    override fun incidentCreationFailed() {
        Log.w(TAG, "incident creation failed")
    }

    private const val TAG = "MotoSOS.IncidentRemote"
}
