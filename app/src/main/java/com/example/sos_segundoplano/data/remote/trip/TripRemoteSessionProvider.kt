package com.example.sos_segundoplano.data.remote.trip

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.example.sos_segundoplano.BuildConfig
import com.example.sos_segundoplano.core.auth.AuthProvider
import com.example.sos_segundoplano.data.remote.auth.AuthNetworkFactory
import com.example.sos_segundoplano.data.local.auth.LinkedMobileDeviceIdStore
import com.example.sos_segundoplano.domain.auth.SessionState
import com.example.sos_segundoplano.data.remote.incident.AndroidCurrentManualSosLocationProvider
import com.example.sos_segundoplano.data.remote.incident.TripSignalManualSosLocationProvider
import com.example.sos_segundoplano.data.signals.TripSignalStoreProvider
import com.example.sos_segundoplano.data.trip.TripSessionStoreProvider
import com.example.sos_segundoplano.data.trip.TripTimingStoreProvider
import com.example.sos_segundoplano.data.trip.TripLocalStateReconciler
import com.example.sos_segundoplano.domain.model.TripSessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class TripRemoteSessionDependencies(
    val store: RemoteTripSessionStore,
    val reconciler: TripRemoteSessionReconciler,
    val starter: RemoteTripStarter,
    val resolvedStarter: ResolvedRemoteTripStarter,
    val startLocationCaptureStates: StateFlow<TripStartLocationCaptureState>,
    val finisher: RemoteTripIdFinisher,
    val pendingFinishStore: PendingTripFinishStore,
    val finishCoordinator: UserTripFinishCoordinator,
    val finishRecoveryProcessor: TripFinishRecoveryProcessor,
    val finishRecoveryScheduler: TripFinishRecoveryScheduler
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
        val starter = AuthenticatedRemoteTripStarter(authRepository, remoteDataSource, store) {
            (TripSessionStoreProvider.store.states.value as? TripSessionState.Active)?.tripSessionKey
        }
        val finisher = AuthenticatedRemoteTripFinisher(authRepository, remoteDataSource, store)
        val pendingFinishStore = SharedPreferencesPendingTripFinishStore(context)
        val finishRecoveryScheduler = TripFinishRecoveryScheduler(context)
        val localStateReconciler = TripLocalStateReconciler(
            remoteTripStore = store,
            tripSessionStore = TripSessionStoreProvider.store,
            tripTimingStore = TripTimingStoreProvider.store
        )
        val currentRiderOwnerId = {
            when (val session = authRepository.observeSession().value) {
                is SessionState.Authenticated -> session.user.takeIf { it.role == com.example.sos_segundoplano.domain.auth.UserRole.Rider }?.id
                is SessionState.Refreshing -> session.user.takeIf { it.role == com.example.sos_segundoplano.domain.auth.UserRole.Rider }?.id
                else -> null
            }
        }
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        val isValidatedInternetAvailable = {
            val network = connectivityManager.activeNetwork
            network != null && connectivityManager.getNetworkCapabilities(network)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        }
        val finishCoordinator = UserTripFinishCoordinator(
            finisher = finisher,
            remoteTripStore = store,
            pendingStore = pendingFinishStore,
            scheduler = finishRecoveryScheduler,
            currentOwnerUserId = currentRiderOwnerId,
            isInternetAvailable = isValidatedInternetAvailable
        )
        val finishRecoveryProcessor = TripFinishRecoveryProcessor(
            finisher = finisher,
            pendingStore = pendingFinishStore,
            scheduler = finishRecoveryScheduler,
            localStateReconciler = localStateReconciler,
            currentOwnerUserId = currentRiderOwnerId,
            isInternetAvailable = isValidatedInternetAvailable
        )
        val startLocationCaptureStates = MutableStateFlow(TripStartLocationCaptureState.Idle)
        return TripRemoteSessionDependencies(
            store = store,
            reconciler = TripRemoteSessionReconciler(
                authRepository = authRepository,
                remoteDataSource = remoteDataSource,
                store = store,
                logger = AndroidTripRemoteSessionLogger,
                tripSessionKey = {
                    (TripSessionStoreProvider.store.states.value as? TripSessionState.Active)?.tripSessionKey
                },
            ),
            starter = starter,
            resolvedStarter = DefaultResolvedRemoteTripStarter(
                resourcesResolver = AuthenticatedTripStartResourcesResolver(
                    authRepository,
                    remoteDataSource,
                    onMobileDeviceResolved = { mobileDeviceId ->
                        val email = when (val state = authRepository.observeSession().value) {
                            is SessionState.Authenticated -> state.user.email
                            is SessionState.Refreshing -> state.user.email
                            else -> null
                        }
                        if (!email.isNullOrBlank()) {
                            LinkedMobileDeviceIdStore(context).saveForAccount(email, mobileDeviceId)
                        }
                    }
                ),
                remoteTripStarter = starter,
                locationProvider = TripSignalManualSosLocationProvider(
                    TripSignalStoreProvider.store,
                    currentLocationProvider = AndroidCurrentManualSosLocationProvider(
                        context,
                        timeoutMillis = START_LOCATION_TIMEOUT_MILLIS
                    ),
                    currentLocationTimeoutMillis = START_LOCATION_TIMEOUT_MILLIS
                ),
                onLocationCaptureStateChanged = { startLocationCaptureStates.value = it }
            ),
            startLocationCaptureStates = startLocationCaptureStates,
            finisher = finisher,
            pendingFinishStore = pendingFinishStore,
            finishCoordinator = finishCoordinator,
            finishRecoveryProcessor = finishRecoveryProcessor,
            finishRecoveryScheduler = finishRecoveryScheduler
        )
    }

    private const val START_LOCATION_TIMEOUT_MILLIS = 20_000L
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
