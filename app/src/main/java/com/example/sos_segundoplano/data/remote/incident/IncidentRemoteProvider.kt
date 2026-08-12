package com.example.sos_segundoplano.data.remote.incident

import android.content.Context
import android.util.Log
import com.example.sos_segundoplano.BuildConfig
import com.example.sos_segundoplano.core.auth.AuthProvider
import com.example.sos_segundoplano.data.offline.OfflineQueueProvider
import com.example.sos_segundoplano.data.remote.auth.AuthNetworkFactory
import com.example.sos_segundoplano.data.remote.trip.TripRemoteSessionProvider
import com.example.sos_segundoplano.data.signals.TripSignalStoreProvider
import com.example.sos_segundoplano.data.validation.FalsePositiveValidationCoordinatorProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object IncidentRemoteProvider {
    @Volatile private var creator: IncidentRemoteCreator? = null
    @Volatile private var manualCoordinator: ManualSosIncidentCoordinator? = null
    @Volatile private var linkStore: RemoteIncidentLinkStore? = null
    private val manualScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun initialize(context: Context): IncidentRemoteCreator = get(context).also { remoteCreator ->
        FalsePositiveValidationCoordinatorProvider.setIncidentRemoteCreator(remoteCreator)
    }

    fun get(context: Context): IncidentRemoteCreator = creator ?: synchronized(this) {
        creator ?: create(context.applicationContext).also { creator = it }
    }

    fun requestManualSos(context: Context) {
        val coordinator = getManualCoordinator(context.applicationContext)
        manualScope.launch { coordinator.requestManualSos() }
    }

    private fun create(context: Context): IncidentRemoteCreator {
        val moshi = AuthNetworkFactory.createMoshi()
        val api = AuthNetworkFactory.createIncidentsApi(BuildConfig.MOTOSOS_API_BASE_URL, moshi)
        val tripSession = TripRemoteSessionProvider.get(context)
        return AuthenticatedIncidentRemoteCreator(
            authRepository = AuthProvider.get(context),
            remoteDataSource = RetrofitIncidentRemoteDataSource(api, moshi),
            activeTripRemoteResolver = tripSession.reconciler,
            remoteTripSessionStore = tripSession.store,
            remoteIncidentLinkStore = getLinkStore(context),
            logger = AndroidIncidentRemoteLogger
        )
    }

    private fun getManualCoordinator(context: Context): ManualSosIncidentCoordinator =
        manualCoordinator ?: synchronized(this) {
            manualCoordinator ?: createManualCoordinator(context).also {
                manualCoordinator = it
            }
        }

    private fun createManualCoordinator(context: Context): ManualSosIncidentCoordinator {
        val moshi = AuthNetworkFactory.createMoshi()
        val tripSession = TripRemoteSessionProvider.get(context)
        val links = getLinkStore(context)
        return ManualSosIncidentCoordinator(
            remoteCreator = AuthenticatedManualSosAlertCreator(
                authRepository = AuthProvider.get(context),
                remoteDataSource = RetrofitManualSosAlertRemoteDataSource(
                    AuthNetworkFactory.createMobileSosAlertsApi(BuildConfig.MOTOSOS_API_BASE_URL, moshi),
                    moshi
                ),
                activeTripRemoteResolver = tripSession.reconciler,
                remoteTripSessionStore = tripSession.store,
                remoteIncidentLinkStore = links,
                locationProvider = TripSignalManualSosLocationProvider(TripSignalStoreProvider.store)
            ),
            offlineEventSink = OfflineQueueProvider.get(context).repository,
            remoteIncidentLinkStore = links
        )
    }

    private fun getLinkStore(context: Context): RemoteIncidentLinkStore = linkStore ?: synchronized(this) {
        linkStore ?: SharedPreferencesRemoteIncidentLinkStore(context).also { linkStore = it }
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

    override fun remoteIncidentPersistenceFailed() {
        Log.w(TAG, "remote incident persistence failed")
    }

    private const val TAG = "MotoSOS.IncidentRemote"
}
