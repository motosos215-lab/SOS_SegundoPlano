package com.example.sos_segundoplano.data.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.example.sos_segundoplano.data.validation.FalsePositiveValidationCoordinatorProvider
import com.example.sos_segundoplano.data.remote.incident.IncidentRemoteProvider
import com.example.sos_segundoplano.data.remote.trip.TripRemoteSessionProvider
import com.example.sos_segundoplano.data.signals.TripSignalStoreProvider
import com.example.sos_segundoplano.data.trip.TripLocalStateReconciler
import com.example.sos_segundoplano.data.trip.TripSessionStoreProvider
import com.example.sos_segundoplano.data.trip.TripTimingStoreProvider
import com.example.sos_segundoplano.core.auth.AuthProvider
import com.example.sos_segundoplano.domain.auth.SessionState
import com.example.sos_segundoplano.domain.auth.UserRole
import com.example.sos_segundoplano.domain.offline.ConnectivitySyncSnapshot
import com.example.sos_segundoplano.domain.offline.NoOpOfflineEventSink
import com.example.sos_segundoplano.domain.offline.OfflineEventTransport
import com.example.sos_segundoplano.domain.offline.OfflineQueueConfig
import com.example.sos_segundoplano.domain.offline.OfflineQueuePolicy
import com.example.sos_segundoplano.domain.offline.SystemWallClock
import com.example.sos_segundoplano.domain.offline.WallClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class OfflineQueueDependencies(
    val database: OfflineQueueDatabase,
    val repository: RoomOfflineQueueRepository,
    val scheduler: OfflineQueueWorkScheduler,
    val transport: OfflineEventTransport,
    val policy: OfflineQueuePolicy,
    val clock: WallClock,
    val config: OfflineQueueConfig,
    val processor: OfflineQueueSyncProcessor,
    val automaticSosProcessor: AutomaticSosBundleRecoveryProcessor,
    val automaticTripFinalizationProcessor: AutomaticTripFinalizationProcessor
)

private enum class DependencyOwnership { ProductionOwned, TestOwned }

private data class InstalledOfflineQueueDependencies(
    val dependencies: OfflineQueueDependencies,
    val ownership: DependencyOwnership
)

object OfflineQueueProvider {
    @Volatile private var installed: InstalledOfflineQueueDependencies? = null
    @Volatile private var lastInitializationScheduleResult: ScheduleResult? = null
    private val schedulingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var riderSchedulingObserverInstalled = false
    @Volatile private var connectivitySchedulingObserverInstalled = false
    private const val AUTOMATIC_SOS_REPAIR_PREFS = "offline_queue_repairs_v1"
    private const val AUTOMATIC_SOS_REPAIR_KEY = "requeue_legacy_automatic_sos_failed_20260820_v1"

    fun initialize(context: Context): OfflineQueueDependencies {
        val deps = get(context)
        FalsePositiveValidationCoordinatorProvider.setOfflineEventSink(deps.repository)
        lastInitializationScheduleResult = deps.repository.scheduleImmediateSyncSafely()
        deps.repository.scheduleImmediateAutomaticSosSafely()
        deps.scheduler.scheduleImmediateManualSos()
        schedulingScope.launch {
            // Repair rows that the previous experimental generic transport may have marked
            // permanently failed even though their event types are not documented by the backend.
            deps.repository.restoreUnsupportedGenericTransportFailures()
        }
        installRiderSchedulingObserver(context.applicationContext, deps)
        installConnectivitySchedulingObserver(context.applicationContext, deps)
        return deps
    }

    fun get(context: Context): OfflineQueueDependencies = installed?.dependencies ?: synchronized(this) {
        installed?.dependencies ?: create(context.applicationContext).also {
            installed = InstalledOfflineQueueDependencies(it, DependencyOwnership.ProductionOwned)
        }
    }

    fun installForTests(testDependencies: OfflineQueueDependencies) {
        synchronized(this) {
            closeIfTestOwned(installed)
            installed = InstalledOfflineQueueDependencies(testDependencies, DependencyOwnership.TestOwned)
            lastInitializationScheduleResult = null
        }
        FalsePositiveValidationCoordinatorProvider.setOfflineEventSink(testDependencies.repository)
    }

    fun resetForTests() {
        synchronized(this) {
            closeIfTestOwned(installed)
            installed = null
            lastInitializationScheduleResult = null
        }
        FalsePositiveValidationCoordinatorProvider.setOfflineEventSink(NoOpOfflineEventSink)
    }

    fun initializationScheduleResultForDiagnostics(): ScheduleResult? = lastInitializationScheduleResult

    private fun closeIfTestOwned(current: InstalledOfflineQueueDependencies?) {
        if (current?.ownership == DependencyOwnership.TestOwned) current.dependencies.database.close()
    }

    private fun create(context: Context): OfflineQueueDependencies {
        val config = OfflineQueueConfig()
        val database = OfflineQueueDatabase.create(context)
        val scheduler = OfflineQueueWorkScheduler(context)
        val clock = SystemWallClock
        val authRepository = AuthProvider.get(context)
        val trip = TripRemoteSessionProvider.get(context)
        val signalStore = TripSignalStoreProvider.store

        fun currentRiderUser() = when (val session = authRepository.observeSession().value) {
            is SessionState.Authenticated -> session.user.takeIf { it.role == UserRole.Rider }
            is SessionState.Refreshing -> session.user.takeIf { it.role == UserRole.Rider }
            else -> null
        }

        fun connectivitySnapshot(): ConnectivitySyncSnapshot? = signalStore.snapshots.value.connectivity.sample?.let { sample ->
            ConnectivitySyncSnapshot(
                connected = sample.connected,
                validated = sample.validated,
                metered = sample.metered,
                transport = sample.transport.name,
                timestampMillis = sample.timestampMillis
            )
        }

        val repository = RoomOfflineQueueRepository(
            queueDao = database.offlineQueueDao(),
            errorDao = database.syncErrorDao(),
            crypto = AndroidKeystoreAesGcmCrypto(config.encryptionKeyVersion),
            serializer = OfflineQueueSerializer(),
            clock = clock,
            config = config,
            scheduler = scheduler,
            currentRiderOwnerId = { currentRiderUser()?.id },
            currentRemoteTripId = { trip.store.remoteTripId.value },
            connectivitySnapshotProvider = ::connectivitySnapshot
        )

        // The current mobile API contract does not define a generic ingestion contract for
        // MinorEvent / LocalIncident / AlertDispatchRequest. Uploading those rows through an
        // undocumented endpoint caused permanent sync errors in the field. Keep secondary events
        // durable and paused until the backend explicitly accepts these types. Automatic SOS uses
        // its canonical /api/v1/mobile/sos-alerts recovery path and is NOT blocked by this transport.
        val transport: OfflineEventTransport = UnconfiguredOfflineEventTransport()
        val policy = OfflineQueuePolicy(config)
        val finisher = trip.finisher
        val localTripReconciler = TripLocalStateReconciler(
            remoteTripStore = trip.store,
            tripSessionStore = TripSessionStoreProvider.store,
            tripTimingStore = TripTimingStoreProvider.store
        )
        return OfflineQueueDependencies(
            database = database,
            repository = repository,
            scheduler = scheduler,
            transport = transport,
            policy = policy,
            clock = clock,
            config = config,
            processor = OfflineQueueSyncProcessor(repository, transport, policy, clock, config),
            automaticSosProcessor = AutomaticSosBundleRecoveryProcessor(
                repository,
                IncidentRemoteProvider.automaticSosAlertCreator(context),
                clock
            ),
            automaticTripFinalizationProcessor = AutomaticTripFinalizationProcessor(
                repository, finisher,
                reconcileLocal = localTripReconciler::reconcileFinishedRemoteTrip,
                clock = clock
            )
        )
    }

    private fun installRiderSchedulingObserver(context: Context, deps: OfflineQueueDependencies) {
        if (riderSchedulingObserverInstalled) return
        synchronized(this) {
            if (riderSchedulingObserverInstalled) return
            riderSchedulingObserverInstalled = true
            schedulingScope.launch {
                AuthProvider.get(context).observeSession().collect { session ->
                    val rider = when (session) {
                        is SessionState.Authenticated -> session.user.role == UserRole.Rider
                        is SessionState.Refreshing -> session.user.role == UserRole.Rider
                        else -> false
                    }
                    if (rider) {
                        val repairPrefs = context.getSharedPreferences(AUTOMATIC_SOS_REPAIR_PREFS, Context.MODE_PRIVATE)
                        if (!repairPrefs.getBoolean(AUTOMATIC_SOS_REPAIR_KEY, false)) {
                            deps.repository.restoreLegacyAutomaticSosFailuresForCurrentRider()
                            repairPrefs.edit().putBoolean(AUTOMATIC_SOS_REPAIR_KEY, true).apply()
                        }
                        deps.repository.expediteAutomaticSosRetriesForCurrentRider()
                        deps.repository.scheduleImmediateAutomaticSosSafely()
                        // A pending manual SOS is stored outside the generic queue and has its own worker.
                        deps.scheduler.scheduleImmediateManualSos()
                    }
                }
            }
        }
    }
    private fun installConnectivitySchedulingObserver(context: Context, deps: OfflineQueueDependencies) {
        if (connectivitySchedulingObserverInstalled) return
        synchronized(this) {
            if (connectivitySchedulingObserverInstalled) return
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = scheduleIfInternetValidated(network)

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                        scheduleEmergencyRecoveryNow()
                    }
                }

                private fun scheduleIfInternetValidated(network: Network) {
                    val capabilities = manager.getNetworkCapabilities(network) ?: return
                    if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                        // Validated default network can be Wi-Fi or cellular/mobile data.
                        scheduleEmergencyRecoveryNow()
                    }
                }

                private fun scheduleEmergencyRecoveryNow() {
                    schedulingScope.launch {
                        // Wi-Fi and cellular data both resume pending remote work immediately.
                        // Emergency recovery remains on its dedicated WorkManager chain.
                        deps.repository.expediteAutomaticSosRetriesForCurrentRider()
                        deps.repository.scheduleImmediateAutomaticSosSafely()
                        deps.scheduler.scheduleImmediateManualSos()
                    }
                }
            }
            try {
                manager.registerDefaultNetworkCallback(callback)
                connectivitySchedulingObserverInstalled = true
                manager.activeNetwork?.let(callback::onAvailable)
            } catch (_: SecurityException) {
                // WorkManager's CONNECTED constraint remains the fallback when observation is unavailable.
            }
        }
    }

}
