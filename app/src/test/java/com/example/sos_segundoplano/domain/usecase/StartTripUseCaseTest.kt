package com.example.sos_segundoplano.domain.usecase

import com.example.sos_segundoplano.domain.model.TripSessionState
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StartTripUseCaseTest {
    private val startTripUseCase = StartTripUseCase()

    @Test
    fun idleStateChangesToActiveWhenTripStarts() {
        val result = startTripUseCase(TripSessionState.Idle)

        assertTrue(result is TripSessionState.Active)
    }

    @Test
    fun activeStateRemainsActiveWhenTripStartsAgain() {
        val active = TripSessionState.Active("trip-session-test")
        val result = startTripUseCase(active)

        assertSame(active, result)
    }

    @Test
    fun startTripOnlyProducesActiveState() {
        val results = listOf(
            startTripUseCase(TripSessionState.Idle),
            startTripUseCase(TripSessionState.Active("trip-session-test"))
        )

        results.forEach { result ->
            assertTrue(result is TripSessionState.Active)
        }
    }

    @Test
    fun startTripDoesNotMutateCurrentStateReference() {
        val currentState = TripSessionState.Idle

        startTripUseCase(currentState)

        assertSame(TripSessionState.Idle, currentState)
    }

    @Test
    fun startTripIsIdempotentAfterFirstActivation() {
        val firstResult = startTripUseCase(TripSessionState.Idle)
        val secondResult = startTripUseCase(firstResult)

        assertTrue(firstResult is TripSessionState.Active)
        assertSame(firstResult, secondResult)
    }
}
