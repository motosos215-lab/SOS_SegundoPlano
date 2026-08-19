package com.example.sos_segundoplano.data.trip

import android.content.Context
import com.example.sos_segundoplano.core.background.AndroidMonitoringServiceStarter
import com.example.sos_segundoplano.core.background.MonitoringServiceStartResult
import com.example.sos_segundoplano.core.background.MonitoringServiceStarter
import com.example.sos_segundoplano.core.permissions.AppNotificationStatus
import com.example.sos_segundoplano.core.permissions.AppNotificationStatusChecker
import com.example.sos_segundoplano.core.permissions.BackgroundLocationPermissionChecker
import com.example.sos_segundoplano.core.permissions.BackgroundLocationPermissionStatus
import com.example.sos_segundoplano.core.permissions.BluetoothRequirementChecker
import com.example.sos_segundoplano.core.permissions.BluetoothRequirementStatus
import com.example.sos_segundoplano.data.remote.trip.ActiveTripLookupResult
import com.example.sos_segundoplano.data.remote.trip.ActiveTripRemoteResolver
import com.example.sos_segundoplano.data.remote.trip.RemoteTripSessionStore
import com.example.sos_segundoplano.data.remote.trip.TripRemoteSessionProvider
import com.example.sos_segundoplano.domain.auth.AuthSessionIdentity
import com.example.sos_segundoplano.domain.auth.UserRole
import com.example.sos_segundoplano.domain.model.TripSessionState
import com.example.sos_segundoplano.domain.trip.TripTimingState
import com.example.sos_segundoplano.domain.trip.TripTimingStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class MonitoringRecoveryReadiness { Ready, LocationMissing, NotificationsMissing, BluetoothMissing }

fun interface MonitoringRecoveryReadinessProvider {
    fun getStatus(): MonitoringRecoveryReadiness
}

enum class RecoveredTripTimingStatus { Continued, RestartedAtRecovery, Unknown }

sealed interface RecoveredMonitoringStatus {
    data object Started : RecoveredMonitoringStatus
    data object AlreadyStarted : RecoveredMonitoringStatus
    data class Blocked(val readiness: MonitoringRecoveryReadiness) : RecoveredMonitoringStatus
    data object Failed : RecoveredMonitoringStatus
}

sealed interface TripProcessRecoveryState {
    data object NotStarted : TripProcessRecoveryState
    data class Restoring(val identity: AuthSessionIdentity) : TripProcessRecoveryState
    data class Idle(val identity: AuthSessionIdentity) : TripProcessRecoveryState
    data class Active(
        val identity: AuthSessionIdentity,
        val remoteTripId: String,
        val timingStatus: RecoveredTripTimingStatus,
        val monitoringStatus: RecoveredMonitoringStatus
    ) : TripProcessRecoveryState
    data class RetryableFailure(val identity: AuthSessionIdentity, val reason: String) : TripProcessRecoveryState
    data object NotApplicable : TripProcessRecoveryState
}

class TripProcessRecoveryCoordinator(
    private val activeTripResolver: ActiveTripRemoteResolver,
    private val remoteTripStore: RemoteTripSessionStore,
    private val tripSessionStore: TripSessionStore,
    private val tripTimingStore: TripTimingStore,
    private val readinessProvider: MonitoringRecoveryReadinessProvider,
    private val monitoringServiceStarter: MonitoringServiceStarter
) {
    private val mutex = Mutex()
    private val mutableStates = MutableStateFlow<TripProcessRecoveryState>(TripProcessRecoveryState.NotStarted)
    val states: StateFlow<TripProcessRecoveryState> = mutableStates.asStateFlow()
    private var monitoringStartedForTripId: String? = null

    suspend fun recover(
        identity: AuthSessionIdentity,
        role: UserRole,
        retry: Boolean = false
    ): TripProcessRecoveryState = mutex.withLock {
        if (role != UserRole.Rider) {
            mutableStates.value = TripProcessRecoveryState.NotApplicable
            return@withLock mutableStates.value
        }

        val current = mutableStates.value
        val sameIdentity = current.identityOrNull() == identity
        if (sameIdentity && !retry && current !is TripProcessRecoveryState.NotStarted) {
            return@withLock current
        }
        if (!sameIdentity) monitoringStartedForTripId = null

        mutableStates.value = TripProcessRecoveryState.Restoring(identity)
        val previousRemoteTripId = remoteTripStore.remoteTripId.value
        mutableStates.value = when (val result = activeTripResolver.resolveActiveTrip()) {
            is ActiveTripLookupResult.Found -> recoverActiveTrip(identity, result.remoteTripId, previousRemoteTripId)
            ActiveTripLookupResult.NoActiveTrip -> {
                tripTimingStore.clear()
                tripSessionStore.setState(TripSessionState.Idle)
                TripProcessRecoveryState.Idle(identity)
            }
            is ActiveTripLookupResult.HttpError -> TripProcessRecoveryState.RetryableFailure(
                identity,
                if (result.statusCode == 401) "session_authorization_failed" else "trip_lookup_failed"
            )
            is ActiveTripLookupResult.NetworkUnavailable -> TripProcessRecoveryState.RetryableFailure(identity, "network_unavailable")
            is ActiveTripLookupResult.Timeout -> TripProcessRecoveryState.RetryableFailure(identity, "network_timeout")
            is ActiveTripLookupResult.InvalidResponse -> TripProcessRecoveryState.RetryableFailure(identity, "trip_response_invalid")
        }
        mutableStates.value
    }

    suspend fun retryMonitoring(identity: AuthSessionIdentity): TripProcessRecoveryState = mutex.withLock {
        val current = mutableStates.value as? TripProcessRecoveryState.Active ?: return@withLock mutableStates.value
        if (current.identity != identity) return@withLock mutableStates.value
        val updated = current.copy(monitoringStatus = startMonitoringIfReady(current.remoteTripId))
        mutableStates.value = updated
        updated
    }

    private fun recoverActiveTrip(
        identity: AuthSessionIdentity,
        remoteTripId: String,
        previousRemoteTripId: String?
    ): TripProcessRecoveryState {
        // A remote lookup must never invent a new local identity.  Resolve the durable local
        // session first, then correlate any timing state with that exact session key.
        val current = tripSessionStore.states.value as? TripSessionState.Active
            ?: return TripProcessRecoveryState.Idle(identity)
        val currentTiming = tripTimingStore.states.value
        val timingStatus = when {
            previousRemoteTripId == remoteTripId &&
                currentTiming is TripTimingState.Active &&
                currentTiming.tripSessionKey == current.tripSessionKey ->
                RecoveredTripTimingStatus.Continued
            else -> {
                tripTimingStore.beginConfirmedTrip(current.tripSessionKey)
                val recoveredTiming = tripTimingStore.states.value as? TripTimingState.Active
                if (recoveredTiming?.tripSessionKey == current.tripSessionKey) {
                    RecoveredTripTimingStatus.RestartedAtRecovery
                } else {
                    RecoveredTripTimingStatus.Unknown
                }
            }
        }
        return TripProcessRecoveryState.Active(
            identity = identity,
            remoteTripId = remoteTripId,
            timingStatus = timingStatus,
            monitoringStatus = startMonitoringIfReady(remoteTripId)
        )
    }

    private fun startMonitoringIfReady(remoteTripId: String): RecoveredMonitoringStatus {
        if (monitoringStartedForTripId == remoteTripId) return RecoveredMonitoringStatus.AlreadyStarted
        val readiness = readinessProvider.getStatus()
        if (readiness != MonitoringRecoveryReadiness.Ready) return RecoveredMonitoringStatus.Blocked(readiness)
        return when (monitoringServiceStarter.start()) {
            MonitoringServiceStartResult.Started -> {
                monitoringStartedForTripId = remoteTripId
                RecoveredMonitoringStatus.Started
            }
            MonitoringServiceStartResult.Failed -> RecoveredMonitoringStatus.Failed
        }
    }

    private fun TripProcessRecoveryState.identityOrNull(): AuthSessionIdentity? = when (this) {
        is TripProcessRecoveryState.Restoring -> identity
        is TripProcessRecoveryState.Idle -> identity
        is TripProcessRecoveryState.Active -> identity
        is TripProcessRecoveryState.RetryableFailure -> identity
        TripProcessRecoveryState.NotStarted,
        TripProcessRecoveryState.NotApplicable -> null
    }
}

object TripProcessRecoveryProvider {
    @Volatile private var coordinator: TripProcessRecoveryCoordinator? = null

    fun get(context: Context): TripProcessRecoveryCoordinator = coordinator ?: synchronized(this) {
        coordinator ?: create(context.applicationContext).also { coordinator = it }
    }

    private fun create(context: Context): TripProcessRecoveryCoordinator {
        val remoteDependencies = TripRemoteSessionProvider.get(context)
        val location = BackgroundLocationPermissionChecker(context)
        val notifications = AppNotificationStatusChecker(context)
        val bluetooth = BluetoothRequirementChecker(context)
        return TripProcessRecoveryCoordinator(
            activeTripResolver = remoteDependencies.reconciler,
            remoteTripStore = remoteDependencies.store,
            tripSessionStore = TripSessionStoreProvider.store,
            tripTimingStore = TripTimingStoreProvider.store,
            readinessProvider = MonitoringRecoveryReadinessProvider {
                when {
                    location.getStatus() != BackgroundLocationPermissionStatus.Granted -> MonitoringRecoveryReadiness.LocationMissing
                    notifications.getStatus() != AppNotificationStatus.Enabled -> MonitoringRecoveryReadiness.NotificationsMissing
                    bluetooth.getStatus() != BluetoothRequirementStatus.Enabled -> MonitoringRecoveryReadiness.BluetoothMissing
                    else -> MonitoringRecoveryReadiness.Ready
                }
            },
            monitoringServiceStarter = AndroidMonitoringServiceStarter(context, recoveryStart = true)
        )
    }
}
