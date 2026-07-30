package com.example.sos_segundoplano.domain.usecase

import com.example.sos_segundoplano.domain.model.TripSessionState
import org.junit.Assert.assertSame
import org.junit.Test

class StartTripUseCaseTest {
    private val startTripUseCase = StartTripUseCase()

    @Test
    fun idleStateChangesToActiveWhenTripStarts() {
        val result = startTripUseCase(TripSessionState.Idle)

        assertSame(TripSessionState.Active, result)
    }

    @Test
    fun activeStateRemainsActiveWhenTripStartsAgain() {
        val result = startTripUseCase(TripSessionState.Active)

        assertSame(TripSessionState.Active, result)
    }

    @Test
    fun startTripOnlyProducesActiveState() {
        val results = listOf(
            startTripUseCase(TripSessionState.Idle),
            startTripUseCase(TripSessionState.Active)
        )

        results.forEach { result ->
            assertSame(TripSessionState.Active, result)
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

        assertSame(TripSessionState.Active, firstResult)
        assertSame(TripSessionState.Active, secondResult)
    }
}
