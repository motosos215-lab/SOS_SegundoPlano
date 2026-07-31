package com.example.sos_segundoplano.domain.usecase

import com.example.sos_segundoplano.domain.model.TripSessionState
import org.junit.Assert.assertEquals
import org.junit.Test

class FinishTripUseCaseTest {
    private val useCase = FinishTripUseCase()

    @Test
    fun activeProducesIdle() {
        assertEquals(TripSessionState.Idle, useCase(TripSessionState.Active))
    }

    @Test
    fun idleRemainsIdle() {
        assertEquals(TripSessionState.Idle, useCase(TripSessionState.Idle))
    }

    @Test
    fun activeResultIsDeterministic() {
        val firstResult = useCase(TripSessionState.Active)
        val secondResult = useCase(TripSessionState.Active)

        assertEquals(firstResult, secondResult)
    }

    @Test
    fun repeatedIdleInvocationsRemainIdle() {
        val firstResult = useCase(TripSessionState.Idle)
        val secondResult = useCase(firstResult)
        val thirdResult = useCase(secondResult)

        assertEquals(TripSessionState.Idle, firstResult)
        assertEquals(TripSessionState.Idle, secondResult)
        assertEquals(TripSessionState.Idle, thirdResult)
    }
}
