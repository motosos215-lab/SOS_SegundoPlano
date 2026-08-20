package com.example.sos_segundoplano.data.offline

import com.example.sos_segundoplano.data.remote.trip.FinishTripRequestDto
import com.example.sos_segundoplano.data.remote.trip.RemoteTripIdFinisher
import com.example.sos_segundoplano.data.remote.trip.TripMutationResult
import com.example.sos_segundoplano.domain.offline.AutomaticTripFinalizationClaimResult
import com.example.sos_segundoplano.domain.offline.ClaimedAutomaticTripFinalization
import com.example.sos_segundoplano.domain.offline.WallClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticTripFinalizationProcessorTest {
    @Test fun processBundleClaimsExactBundleWithoutClaimNext() = kotlinx.coroutines.runBlocking {
        val claim = ClaimedAutomaticTripFinalization("owner", "A", "trip-A", "token-A")
        val repo = FakeRepository(exact = AutomaticTripFinalizationClaimResult.Acquired(claim))
        val finisher = FakeFinisher()
        AutomaticTripFinalizationProcessor(repo, finisher, { true }, WallClock { 1L }).processBundle("A", "online")
        assertEquals(listOf("A" to "online"), repo.exactCalls)
        assertTrue(repo.nextCalls.isEmpty())
        assertEquals(listOf("trip-A"), finisher.tripIds)
    }
    @Test fun unavailableExactBundleNeverFallsBackToNextBundle() = kotlinx.coroutines.runBlocking {
        val repo = FakeRepository(exact = AutomaticTripFinalizationClaimResult.BusyOrUnavailable)
        val finisher = FakeFinisher()
        AutomaticTripFinalizationProcessor(repo, finisher, { true }, WallClock { 1L }).processBundle("A", "online")
        assertTrue(repo.nextCalls.isEmpty()); assertTrue(finisher.tripIds.isEmpty())
    }
    @Test fun workerUsesClaimNextAndNotExactClaim() = kotlinx.coroutines.runBlocking {
        val claim = ClaimedAutomaticTripFinalization("owner", "B", "trip-B", "token-B")
        val repo = FakeRepository(next = AutomaticTripFinalizationClaimResult.Acquired(claim))
        val finisher = FakeFinisher()
        AutomaticTripFinalizationProcessor(repo, finisher, { true }, WallClock { 1L }).process("worker")
        assertEquals(listOf("worker"), repo.nextCalls); assertTrue(repo.exactCalls.isEmpty()); assertEquals(listOf("trip-B"), finisher.tripIds)
    }
    @Test fun exactBundleAIsIsolatedWhenBundleBAlsoExists() = kotlinx.coroutines.runBlocking {
        val claimA = ClaimedAutomaticTripFinalization("owner", "A", "trip-A", "token-A")
        val claimB = ClaimedAutomaticTripFinalization("owner", "B", "trip-B", "token-B")
        val repo = FakeRepository(exactByBundle = mapOf(
            "A" to AutomaticTripFinalizationClaimResult.Acquired(claimA),
            "B" to AutomaticTripFinalizationClaimResult.Acquired(claimB)
        ))
        val finisher = FakeFinisher()
        AutomaticTripFinalizationProcessor(repo, finisher, { true }, WallClock { 1L }).processBundle("A", "online")
        assertEquals(listOf("A" to "online"), repo.exactCalls)
        assertTrue(repo.nextCalls.isEmpty())
        assertEquals(listOf("trip-A"), finisher.tripIds)
        assertTrue("trip-B" !in finisher.tripIds)
        assertEquals(listOf("token-A"), repo.completedTokens)
    }
}

private class FakeRepository(
    private val exact: AutomaticTripFinalizationClaimResult = AutomaticTripFinalizationClaimResult.BusyOrUnavailable,
    private val next: AutomaticTripFinalizationClaimResult = AutomaticTripFinalizationClaimResult.BusyOrUnavailable,
    private val exactByBundle: Map<String, AutomaticTripFinalizationClaimResult> = emptyMap()
) : AutomaticTripFinalizationRepository {
    val exactCalls = mutableListOf<Pair<String,String>>(); val nextCalls = mutableListOf<String>(); val completedTokens = mutableListOf<String>()
    override suspend fun claimNextAutomaticTripFinalization(workerId:String, now:Long) = next.also { nextCalls += workerId }
    override suspend fun claimAutomaticTripFinalization(bundleKey:String, workerId:String, now:Long) = (exactByBundle[bundleKey] ?: exact).also { exactCalls += bundleKey to workerId }
    override suspend fun completeAutomaticTripFinalization(claim: ClaimedAutomaticTripFinalization, now:Long): Boolean { completedTokens += claim.claimToken; return true }
    override suspend fun releaseAutomaticTripFinalization(claim: ClaimedAutomaticTripFinalization, permanent:Boolean, now:Long) = true
}
private class FakeFinisher : RemoteTripIdFinisher {
    val tripIds = mutableListOf<String>()
    override suspend fun finishTrip(request: FinishTripRequestDto) = TripMutationResult.MissingRequiredData("unused")
    override suspend fun finishTrip(remoteTripId:String, request:FinishTripRequestDto): TripMutationResult { tripIds += remoteTripId; return TripMutationResult.Success(remoteTripId, "Finished") }
}
