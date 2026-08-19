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
import com.example.sos_segundoplano.domain.validation.LocalIncident
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

object IncidentRemoteProvider {
    @Volatile private var creator: IncidentRemoteCreator? = null
    @Volatile private var automaticCreator: AutomaticSosAlertCreator? = null
    @Volatile private var manualCoordinator: ManualSosIncidentCoordinator? = null
    @Volatile private var linkStore: RemoteIncidentLinkStore? = null
    @Volatile private var mobileSosDataSource: ManualSosAlertRemoteDataSource? = null
    private val manualScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableManualSosRequestState = MutableStateFlow<ManualSosRequestState>(ManualSosRequestState.Idle)
    val manualSosRequestState: StateFlow<ManualSosRequestState> = mutableManualSosRequestState.asStateFlow()

    fun clearManualSosRequestState() {
        mutableManualSosRequestState.value = ManualSosRequestState.Idle
    }

    fun initialize(context: Context): IncidentRemoteCreator = get(context).also { remoteCreator ->
        FalsePositiveValidationCoordinatorProvider.setIncidentRemoteCreator(remoteCreator)
        FalsePositiveValidationCoordinatorProvider.setAutomaticSosAlertCreator(getAutomaticCreator(context.applicationContext))
    }

    fun get(context: Context): IncidentRemoteCreator = creator ?: synchronized(this) {
        creator ?: create(context.applicationContext).also { creator = it }
    }

    fun requestManualSos(
        context: Context,
        options: ManualSosSubmissionOptions = ManualSosSubmissionOptions()
    ) {
        val coordinator = getManualCoordinator(context.applicationContext)
        mutableManualSosRequestState.value = ManualSosRequestState.Preparing
        manualScope.launch {
            try {
                val completed = withTimeoutOrNull(MANUAL_SOS_OPERATION_TIMEOUT_MILLIS) {
                    coordinator.requestManualSos(options, ManualSosProgressReporter { state ->
                        mutableManualSosRequestState.value = state
                    })
                }
                if (completed == null) {
                    Log.w(TAG_MANUAL, "event=manual_sos_timeout state=${mutableManualSosRequestState.value.javaClass.simpleName}")
                    mutableManualSosRequestState.value = ManualSosRequestState.RetryableFailure
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Log.w(TAG_MANUAL, "event=manual_sos_failed type=${failure.javaClass.simpleName}")
                mutableManualSosRequestState.value = ManualSosRequestState.RetryableFailure
            }
        }
    }

    fun automaticSosAlertCreator(context: Context): AutomaticSosAlertCreator =
        getAutomaticCreator(context.applicationContext)

    suspend fun requestManualSosAwait(
        context: Context,
        options: ManualSosSubmissionOptions = ManualSosSubmissionOptions()
    ): LocalIncident = getManualCoordinator(context.applicationContext).requestManualSos(options)

    private fun create(context: Context): IncidentRemoteCreator {
        val moshi = AuthNetworkFactory.createMoshi()
        val api = AuthNetworkFactory.createIncidentsApi(BuildConfig.MOTOSOS_API_BASE_URL, moshi)
        val locationPublisher = AuthenticatedEmergencyLocationPublisher(
            authRepository = AuthProvider.get(context),
            api = AuthNetworkFactory.createEmergencyLocationSharingApi(BuildConfig.MOTOSOS_API_BASE_URL, moshi)
        )
        val tripSession = TripRemoteSessionProvider.get(context)
        return AuthenticatedIncidentRemoteCreator(
            authRepository = AuthProvider.get(context),
            remoteDataSource = RetrofitIncidentRemoteDataSource(api, moshi),
            activeTripRemoteResolver = tripSession.reconciler,
            remoteTripSessionStore = tripSession.store,
            remoteIncidentLinkStore = getLinkStore(context),
            eventLocationProvider = TripSignalManualSosLocationProvider(TripSignalStoreProvider.store),
            emergencyLocationPublisher = locationPublisher,
            logger = AndroidIncidentRemoteLogger
        )
    }

    private fun getAutomaticCreator(context: Context): AutomaticSosAlertCreator = automaticCreator ?: synchronized(this) {
        automaticCreator ?: run {
            val tripSession = TripRemoteSessionProvider.get(context)
            AuthenticatedAutomaticSosAlertCreator(
                authRepository = AuthProvider.get(context),
                remoteDataSource = getMobileSosDataSource(context),
                activeTripRemoteResolver = tripSession.reconciler,
                remoteTripSessionStore = tripSession.store,
                locationProvider = TripSignalManualSosLocationProvider(
                    store = TripSignalStoreProvider.store,
                    currentLocationProvider = AndroidCurrentManualSosLocationProvider(context)
                ),
                emergencyLocationPublisher = AuthenticatedEmergencyLocationPublisher(
                    authRepository = AuthProvider.get(context),
                    api = AuthNetworkFactory.createEmergencyLocationSharingApi(
                        BuildConfig.MOTOSOS_API_BASE_URL,
                        AuthNetworkFactory.createMoshi()
                    )
                )
            ).also { automaticCreator = it }
        }
    }

    private fun getManualCoordinator(context: Context): ManualSosIncidentCoordinator =
        manualCoordinator ?: synchronized(this) {
            manualCoordinator ?: createManualCoordinator(context).also {
                manualCoordinator = it
            }
        }

    private fun createManualCoordinator(context: Context): ManualSosIncidentCoordinator {
        val moshi = AuthNetworkFactory.createMoshi()
        val locationPublisher = AuthenticatedEmergencyLocationPublisher(
            authRepository = AuthProvider.get(context),
            api = AuthNetworkFactory.createEmergencyLocationSharingApi(BuildConfig.MOTOSOS_API_BASE_URL, moshi)
        )
        val tripSession = TripRemoteSessionProvider.get(context)
        val links = getLinkStore(context)
        return ManualSosIncidentCoordinator(
            remoteCreator = AuthenticatedManualSosAlertCreator(
                authRepository = AuthProvider.get(context),
                remoteDataSource = getMobileSosDataSource(context),
                activeTripRemoteResolver = tripSession.reconciler,
                remoteTripSessionStore = tripSession.store,
                remoteIncidentLinkStore = links,
                locationProvider = TripSignalManualSosLocationProvider(
                    store = TripSignalStoreProvider.store,
                    currentLocationProvider = AndroidCurrentManualSosLocationProvider(context)
                ),
                emergencyLocationPublisher = locationPublisher
            ),
            offlineEventSink = OfflineQueueProvider.get(context).repository,
            remoteIncidentLinkStore = links
        )
    }

    private fun getMobileSosDataSource(context: Context): ManualSosAlertRemoteDataSource = mobileSosDataSource ?: synchronized(this) {
        mobileSosDataSource ?: run {
            val moshi = AuthNetworkFactory.createMoshi()
            RetrofitManualSosAlertRemoteDataSource(
                AuthNetworkFactory.createMobileSosAlertsApi(BuildConfig.MOTOSOS_API_BASE_URL, moshi),
                moshi
            ).also { mobileSosDataSource = it }
        }
    }

    private fun getLinkStore(context: Context): RemoteIncidentLinkStore = linkStore ?: synchronized(this) {
        linkStore ?: SharedPreferencesRemoteIncidentLinkStore(context).also { linkStore = it }
    }

    private const val MANUAL_SOS_OPERATION_TIMEOUT_MILLIS = 30_000L
    private const val TAG_MANUAL = "MotoSOS.ManualSos"
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
