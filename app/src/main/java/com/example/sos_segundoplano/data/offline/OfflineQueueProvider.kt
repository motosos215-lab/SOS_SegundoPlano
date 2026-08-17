package com.example.sos_segundoplano.data.offline

import android.content.Context
import com.example.sos_segundoplano.data.validation.FalsePositiveValidationCoordinatorProvider
import com.example.sos_segundoplano.data.remote.incident.IncidentRemoteProvider
import com.example.sos_segundoplano.core.auth.AuthProvider
import com.example.sos_segundoplano.domain.auth.SessionState
import com.example.sos_segundoplano.domain.auth.UserRole
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
    val automaticSosProcessor: AutomaticSosBundleRecoveryProcessor
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

    fun initialize(context: Context): OfflineQueueDependencies {
        val deps = get(context)
        FalsePositiveValidationCoordinatorProvider.setOfflineEventSink(deps.repository)
        lastInitializationScheduleResult = deps.repository.scheduleImmediateSyncSafely()
        installRiderSchedulingObserver(context.applicationContext, deps)
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
        val repository = RoomOfflineQueueRepository(
            queueDao = database.offlineQueueDao(),
            errorDao = database.syncErrorDao(),
            crypto = AndroidKeystoreAesGcmCrypto(config.encryptionKeyVersion),
            serializer = OfflineQueueSerializer(),
            clock = clock,
            config = config,
            scheduler = scheduler,
            currentRiderOwnerId = {
                when (val session = AuthProvider.get(context).observeSession().value) {
                    is SessionState.Authenticated -> session.user.takeIf { it.role == UserRole.Rider }?.id
                    is SessionState.Refreshing -> session.user.takeIf { it.role == UserRole.Rider }?.id
                    else -> null
                }
            }
        )
        val transport = UnconfiguredOfflineEventTransport()
        val policy = OfflineQueuePolicy(config)
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
                    if (rider) deps.repository.scheduleImmediateSyncSafely()
                }
            }
        }
    }
}
