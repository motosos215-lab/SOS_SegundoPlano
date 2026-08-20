package com.example.sos_segundoplano.features.trip

import org.junit.Assert.assertEquals
import org.junit.Test

class RiderSyncUiStateTest {
    @Test
    fun `pending count includes remote backed SOS trip finish and route data`() {
        val state = RiderSyncUiState(
            automaticSosPendingCount = 2,
            manualSosPending = true,
            tripFinishPending = true,
            routePointPendingCount = 5
        )

        assertEquals(9, state.pendingCount)
        assertEquals(0, state.attentionCount)
    }

    @Test
    fun `attention count excludes locally retained secondary events`() {
        val state = RiderSyncUiState(
            automaticSosFailedCount = 1,
            tripFinishNeedsAttention = true
        )

        assertEquals(0, state.pendingCount)
        assertEquals(2, state.attentionCount)
    }
}
