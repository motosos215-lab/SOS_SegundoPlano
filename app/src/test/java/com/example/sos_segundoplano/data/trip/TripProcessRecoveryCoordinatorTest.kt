package com.example.sos_segundoplano.data.trip

import com.example.sos_segundoplano.core.background.MonitoringServiceStartResult
import com.example.sos_segundoplano.core.background.MonitoringServiceStarter
import com.example.sos_segundoplano.data.remote.trip.ActiveTripLookupResult
import com.example.sos_segundoplano.data.remote.trip.ActiveTripRemoteResolver
import com.example.sos_segundoplano.data.remote.trip.InMemoryRemoteTripSessionStore
import com.example.sos_segundoplano.domain.auth.AuthSessionIdentity
import com.example.sos_segundoplano.domain.auth.UserRole
import com.example.sos_segundoplano.domain.model.TripSessionState
import com.example.sos_segundoplano.domain.trip.TripTimingState
import com.example.sos_segundoplano.domain.trip.TripTimingStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripProcessRecoveryCoordinatorTest {
    private val identity = AuthSessionIdentity("rider-1", 7L)

    @Test fun riderWithoutRemoteActiveTripRestoresIdleAndClearsStaleMetadata() = runBlocking {
        val remoteStore = InMemoryRemoteTripSessionStore().apply { setRemoteTripId("stale-trip") }
        val timing = FakeTimingStore(TripTimingState.Active(100L))
        val sessions = InMemoryTripSessionStore(TripSessionState.Active)
        val coordinator = coordinator(
            resolver = FakeResolver(ActiveTripLookupResult.NoActiveTrip, remoteStore),
            remoteStore = remoteStore,
            sessions = sessions,
            timing = timing
        )

        val state = coordinator.recover(identity, UserRole.Rider)

        assertTrue(state is TripProcessRecoveryState.Idle)
        assertEquals(TripSessionState.Idle, sessions.states.value)
        assertEquals(null, remoteStore.remoteTripId.value)
        assertEquals(TripTimingState.Unknown, timing.states.value)
    }

    @Test fun riderWithRemoteActiveTripRestoresActiveWithoutCallingRemoteStart() = runBlocking {
        val remoteStore = InMemoryRemoteTripSessionStore().apply { setRemoteTripId("remote-trip-1") }
        val resolver = FakeResolver(ActiveTripLookupResult.Found("remote-trip-1"), remoteStore)
        val sessions = InMemoryTripSessionStore()
        val timing = FakeTimingStore(TripTimingState.Active(500L))
        val service = FakeServiceStarter()
        val coordinator = coordinator(resolver, remoteStore, sessions, timing, service = service)

        val state = coordinator.recover(identity, UserRole.Rider) as TripProcessRecoveryState.Active

        assertEquals("remote-trip-1", state.remoteTripId)
        assertEquals("remote-trip-1", remoteStore.remoteTripId.value)
        assertEquals(TripSessionState.Active, sessions.states.value)
        assertEquals(RecoveredTripTimingStatus.Continued, state.timingStatus)
        assertEquals(1, resolver.calls)
        assertEquals(1, service.calls)
    }

    @Test fun repeatedRecoveryForSameSessionIsSingleFlightAndDoesNotRestartService() = runBlocking {
        val remoteStore = InMemoryRemoteTripSessionStore().apply { setRemoteTripId("remote-trip-1") }
        val resolver = FakeResolver(ActiveTripLookupResult.Found("remote-trip-1"), remoteStore)
        val service = FakeServiceStarter()
        val coordinator = coordinator(resolver, remoteStore, service = service)

        coordinator.recover(identity, UserRole.Rider)
        coordinator.recover(identity, UserRole.Rider)

        assertEquals(1, resolver.calls)
        assertEquals(1, service.calls)
    }

    @Test fun temporaryFailurePreservesRemoteTripTimingAndLocalStateForRetry() = runBlocking {
        val remoteStore = InMemoryRemoteTripSessionStore().apply { setRemoteTripId("remote-trip-1") }
        val timing = FakeTimingStore(TripTimingState.Active(500L))
        val sessions = InMemoryTripSessionStore(TripSessionState.Idle)
        val coordinator = coordinator(
            FakeResolver(ActiveTripLookupResult.NetworkUnavailable("offline"), remoteStore),
            remoteStore,
            sessions,
            timing
        )

        val state = coordinator.recover(identity, UserRole.Rider)

        assertTrue(state is TripProcessRecoveryState.RetryableFailure)
        assertEquals("remote-trip-1", remoteStore.remoteTripId.value)
        assertEquals(TripTimingState.Active(500L), timing.states.value)
        assertEquals(TripSessionState.Idle, sessions.states.value)
    }

    @Test fun monitorNeverResolvesRiderTripOrStartsMonitoring() = runBlocking {
        val resolver = FakeResolver(ActiveTripLookupResult.Found("remote-trip-1"))
        val service = FakeServiceStarter()
        val sessions = InMemoryTripSessionStore()
        val coordinator = coordinator(resolver, sessions = sessions, service = service)

        val state = coordinator.recover(identity, UserRole.Monitor)

        assertEquals(TripProcessRecoveryState.NotApplicable, state)
        assertEquals(0, resolver.calls)
        assertEquals(0, service.calls)
        assertEquals(TripSessionState.Idle, sessions.states.value)
    }

    @Test fun missingValidTimingStartsSafeDurationAtRecoveryWithoutInventingHistory() = runBlocking {
        val remoteStore = InMemoryRemoteTripSessionStore().apply { setRemoteTripId("remote-trip-1") }
        val timing = FakeTimingStore(TripTimingState.Unknown, recoveredStartMillis = 2_000L)
        val coordinator = coordinator(
            FakeResolver(ActiveTripLookupResult.Found("remote-trip-1"), remoteStore),
            remoteStore,
            timing = timing
        )

        val state = coordinator.recover(identity, UserRole.Rider) as TripProcessRecoveryState.Active

        assertEquals(RecoveredTripTimingStatus.RestartedAtRecovery, state.timingStatus)
        assertEquals(TripTimingState.Active(2_000L), timing.states.value)
        assertEquals(1, timing.beginCalls)
    }

    @Test fun differentBootTimingIsDiscardedAndRecoveryStartsFromCurrentBoot() = runBlocking {
        val persistence = TestTimingPersistence(PersistedTripTiming(4_000L, bootSessionId = 3L))
        val timing = DefaultTripTimingStore(
            persistence = persistence,
            clock = com.example.sos_segundoplano.domain.trip.ElapsedRealtimeClock { 8_000L },
            bootSessionProvider = BootSessionProvider { 4L }
        )
        val remoteStore = InMemoryRemoteTripSessionStore().apply { setRemoteTripId("remote-trip-1") }
        val coordinator = TripProcessRecoveryCoordinator(
            FakeResolver(ActiveTripLookupResult.Found("remote-trip-1"), remoteStore),
            remoteStore,
            InMemoryTripSessionStore(),
            timing,
            MutableReadiness(MonitoringRecoveryReadiness.Ready),
            FakeServiceStarter()
        )

        val state = coordinator.recover(identity, UserRole.Rider) as TripProcessRecoveryState.Active

        assertEquals(RecoveredTripTimingStatus.RestartedAtRecovery, state.timingStatus)
        assertEquals(TripTimingState.Active(8_000L), timing.states.value)
        assertEquals(PersistedTripTiming(8_000L, 4L), persistence.value)
    }

    @Test fun differentRemoteTripDoesNotReuseTimingFromStaleTrip() = runBlocking {
        val remoteStore = InMemoryRemoteTripSessionStore().apply { setRemoteTripId("stale-trip") }
        val timing = FakeTimingStore(TripTimingState.Active(100L), recoveredStartMillis = 2_000L)
        val coordinator = coordinator(
            FakeResolver(ActiveTripLookupResult.Found("current-trip"), remoteStore),
            remoteStore,
            timing = timing
        )

        val state = coordinator.recover(identity, UserRole.Rider) as TripProcessRecoveryState.Active

        assertEquals("current-trip", state.remoteTripId)
        assertEquals(RecoveredTripTimingStatus.RestartedAtRecovery, state.timingStatus)
        assertEquals(TripTimingState.Active(2_000L), timing.states.value)
    }

    @Test fun blockedReadinessKeepsTripActiveAndStartsServiceOnlyAfterRetry() = runBlocking {
        val remoteStore = InMemoryRemoteTripSessionStore().apply { setRemoteTripId("remote-trip-1") }
        val readiness = MutableReadiness(MonitoringRecoveryReadiness.NotificationsMissing)
        val service = FakeServiceStarter()
        val coordinator = coordinator(
            FakeResolver(ActiveTripLookupResult.Found("remote-trip-1"), remoteStore),
            remoteStore,
            readiness = readiness,
            service = service
        )

        val first = coordinator.recover(identity, UserRole.Rider) as TripProcessRecoveryState.Active
        assertEquals(
            RecoveredMonitoringStatus.Blocked(MonitoringRecoveryReadiness.NotificationsMissing),
            first.monitoringStatus
        )
        assertEquals(0, service.calls)

        readiness.value = MonitoringRecoveryReadiness.Ready
        val retried = coordinator.retryMonitoring(identity) as TripProcessRecoveryState.Active
        assertEquals(RecoveredMonitoringStatus.Started, retried.monitoringStatus)
        assertEquals(1, service.calls)

        val repeated = coordinator.retryMonitoring(identity) as TripProcessRecoveryState.Active
        assertEquals(RecoveredMonitoringStatus.AlreadyStarted, repeated.monitoringStatus)
        assertEquals(1, service.calls)
    }

    private fun coordinator(
        resolver: FakeResolver,
        remoteStore: InMemoryRemoteTripSessionStore = InMemoryRemoteTripSessionStore(),
        sessions: InMemoryTripSessionStore = InMemoryTripSessionStore(),
        timing: FakeTimingStore = FakeTimingStore(TripTimingState.Unknown),
        readiness: MutableReadiness = MutableReadiness(MonitoringRecoveryReadiness.Ready),
        service: FakeServiceStarter = FakeServiceStarter()
    ) = TripProcessRecoveryCoordinator(resolver, remoteStore, sessions, timing, readiness, service)

    private class FakeResolver(
        private val result: ActiveTripLookupResult,
        private val store: InMemoryRemoteTripSessionStore? = null
    ) : ActiveTripRemoteResolver {
        var calls = 0
        override suspend fun resolveActiveTrip(): ActiveTripLookupResult {
            calls++
            when (result) {
                is ActiveTripLookupResult.Found -> store?.setRemoteTripId(result.remoteTripId)
                ActiveTripLookupResult.NoActiveTrip -> store?.clearRemoteTripId()
                else -> Unit
            }
            return result
        }
    }

    private class FakeTimingStore(
        initial: TripTimingState,
        private val recoveredStartMillis: Long = 1_000L
    ) : TripTimingStore {
        private val mutableStates = MutableStateFlow(initial)
        override val states: StateFlow<TripTimingState> = mutableStates
        var beginCalls = 0
        override fun beginConfirmedTrip() {
            beginCalls++
            mutableStates.value = TripTimingState.Active(recoveredStartMillis)
        }
        override fun clear() {
            mutableStates.value = TripTimingState.Unknown
        }
    }

    private class MutableReadiness(var value: MonitoringRecoveryReadiness) : MonitoringRecoveryReadinessProvider {
        override fun getStatus(): MonitoringRecoveryReadiness = value
    }

    private class TestTimingPersistence(var value: PersistedTripTiming?) : TripTimingPersistence {
        override fun read(): PersistedTripTiming? = value
        override fun save(value: PersistedTripTiming): Boolean {
            this.value = value
            return true
        }
        override fun clear() {
            value = null
        }
    }

    private class FakeServiceStarter : MonitoringServiceStarter {
        var calls = 0
        override fun start(): MonitoringServiceStartResult {
            calls++
            return MonitoringServiceStartResult.Started
        }
    }
}
