package com.example.sos_segundoplano.data.remote.trip

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserTripFinishCoordinatorTest {
    @Test
    fun `offline finish is persisted without calling the API and waits for connectivity`() = runTest {
        val remoteStore = InMemoryRemoteTripSessionStore().apply {
            setActiveSession("remote-trip-1", 1L, TRIP_KEY)
        }
        val pendingStore = FakePendingTripFinishStore()
        val scheduler = FakeTripFinishScheduler()
        val finisher = FakeFinisher(TripMutationResult.Success("remote-trip-1", "Finished"))
        val coordinator = UserTripFinishCoordinator(
            finisher = finisher,
            remoteTripStore = remoteStore,
            pendingStore = pendingStore,
            scheduler = scheduler,
            currentOwnerUserId = { RIDER_ID },
            isInternetAvailable = { false },
            nowEpochMillis = { 10_000L }
        )

        val result = coordinator.finishTrip(TRIP_KEY, FinishTripRequestDto(clientFinishedAtUtc = FINISHED_AT))

        assertEquals(UserTripFinishResult.SavedForSync, result)
        assertEquals(0, finisher.callCount)
        assertEquals(PendingTripFinishState.RetryPending, pendingStore.state.value?.state)
        assertEquals(FINISHED_AT, pendingStore.state.value?.clientFinishedAtUtc)
        assertEquals(1, scheduler.immediateCount)
    }

    @Test
    fun `network failure is persisted and scheduled instead of blocking local finish`() = runTest {
        val remoteStore = InMemoryRemoteTripSessionStore().apply {
            setActiveSession("remote-trip-1", 1L, TRIP_KEY)
        }
        val pendingStore = FakePendingTripFinishStore()
        val scheduler = FakeTripFinishScheduler()
        val coordinator = UserTripFinishCoordinator(
            finisher = FakeFinisher(TripMutationResult.NetworkUnavailable("offline")),
            remoteTripStore = remoteStore,
            pendingStore = pendingStore,
            scheduler = scheduler,
            currentOwnerUserId = { RIDER_ID },
            nowEpochMillis = { 10_000L }
        )

        val result = coordinator.finishTrip(TRIP_KEY, FinishTripRequestDto(clientFinishedAtUtc = FINISHED_AT))

        assertEquals(UserTripFinishResult.SavedForSync, result)
        assertEquals(PendingTripFinishState.RetryPending, pendingStore.state.value?.state)
        assertEquals("remote-trip-1", pendingStore.state.value?.remoteTripId)
        assertEquals(1, scheduler.immediateCount)
        assertEquals("remote-trip-1", remoteStore.remoteTripId.value)
    }

    @Test
    fun `remote success clears durable finish and correlated remote session`() = runTest {
        val remoteStore = InMemoryRemoteTripSessionStore().apply {
            setActiveSession("remote-trip-1", 1L, TRIP_KEY)
        }
        val pendingStore = FakePendingTripFinishStore()
        val coordinator = UserTripFinishCoordinator(
            finisher = FakeFinisher(TripMutationResult.Success("remote-trip-1", "Finished")),
            remoteTripStore = remoteStore,
            pendingStore = pendingStore,
            scheduler = FakeTripFinishScheduler(),
            currentOwnerUserId = { RIDER_ID },
            nowEpochMillis = { 10_000L }
        )

        val result = coordinator.finishTrip(TRIP_KEY, FinishTripRequestDto(clientFinishedAtUtc = FINISHED_AT))

        assertEquals(UserTripFinishResult.RemoteConfirmed, result)
        assertNull(pendingStore.state.value)
        assertNull(remoteStore.remoteTripId.value)
    }

    @Test
    fun `non retryable http rejection remains visible as needs attention`() = runTest {
        val remoteStore = InMemoryRemoteTripSessionStore().apply {
            setActiveSession("remote-trip-1", 1L, TRIP_KEY)
        }
        val pendingStore = FakePendingTripFinishStore()
        val coordinator = UserTripFinishCoordinator(
            finisher = FakeFinisher(TripMutationResult.HttpError(400, "bad_request")),
            remoteTripStore = remoteStore,
            pendingStore = pendingStore,
            scheduler = FakeTripFinishScheduler(),
            currentOwnerUserId = { RIDER_ID },
            nowEpochMillis = { 10_000L }
        )

        val result = coordinator.finishTrip(TRIP_KEY, FinishTripRequestDto(clientFinishedAtUtc = FINISHED_AT))

        assertEquals(UserTripFinishResult.SavedNeedsAttention, result)
        assertEquals(PendingTripFinishState.FailedPermanent, pendingStore.state.value?.state)
        assertTrue(pendingStore.state.value?.lastErrorCode?.startsWith("http_") == true)
    }

    private class FakeFinisher(private val result: TripMutationResult) : RemoteTripIdFinisher {
        var callCount: Int = 0
            private set

        override suspend fun finishTrip(request: FinishTripRequestDto): TripMutationResult {
            callCount += 1
            return result
        }

        override suspend fun finishTrip(remoteTripId: String, request: FinishTripRequestDto): TripMutationResult {
            callCount += 1
            return result
        }
    }

    private class FakeTripFinishScheduler : TripFinishWorkScheduler {
        var immediateCount = 0
        var retryCount = 0
        override fun scheduleImmediate() { immediateCount += 1 }
        override fun scheduleRetry(delayMillis: Long) { retryCount += 1 }
    }

    private class FakePendingTripFinishStore : PendingTripFinishStore {
        private val mutable = MutableStateFlow<PendingTripFinish?>(null)
        override val state: StateFlow<PendingTripFinish?> = mutable

        override fun readForOwner(ownerUserId: String): PendingTripFinish? = mutable.value?.takeIf { it.ownerUserId == ownerUserId }

        override fun savePending(value: PendingTripFinish): Boolean {
            val current = mutable.value
            if (current != null && (current.ownerUserId != value.ownerUserId || current.remoteTripId != value.remoteTripId)) return false
            mutable.value = value
            return true
        }

        override fun markRetry(ownerUserId: String, remoteTripId: String, nextAttemptAtEpochMillis: Long?, errorCode: String?): Boolean {
            val current = readForOwner(ownerUserId)?.takeIf { it.remoteTripId == remoteTripId } ?: return false
            mutable.value = current.copy(
                state = PendingTripFinishState.RetryPending,
                attemptCount = current.attemptCount + 1,
                nextAttemptAtEpochMillis = nextAttemptAtEpochMillis,
                lastErrorCode = errorCode
            )
            return true
        }

        override fun markFailed(ownerUserId: String, remoteTripId: String, errorCode: String?): Boolean {
            val current = readForOwner(ownerUserId)?.takeIf { it.remoteTripId == remoteTripId } ?: return false
            mutable.value = current.copy(state = PendingTripFinishState.FailedPermanent, lastErrorCode = errorCode)
            return true
        }

        override fun clearIfMatches(ownerUserId: String, remoteTripId: String): Boolean {
            val current = mutable.value ?: return true
            if (current.ownerUserId != ownerUserId || current.remoteTripId != remoteTripId) return false
            mutable.value = null
            return true
        }

        override fun expedite(ownerUserId: String, nowEpochMillis: Long): Boolean = true
    }

    private companion object {
        const val RIDER_ID = "rider-1"
        const val TRIP_KEY = "11111111-1111-1111-1111-111111111111"
        const val FINISHED_AT = "2026-08-20T16:00:00Z"
    }
}
