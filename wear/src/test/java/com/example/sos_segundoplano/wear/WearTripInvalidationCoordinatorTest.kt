package com.example.sos_segundoplano.wear

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred

class WearTripInvalidationCoordinatorTest {
    @Test fun startReconcilesBeforeStartingSignals() = runBlocking {
        val events = mutableListOf<String>()
        WearTripInvalidationCoordinator({ events += "start" }, {}, { events += "reconcile" }).onStart()
        assertEquals(listOf("reconcile", "start"), events)
    }
    @Test fun stopStopsSignalsAndReconciles() = runBlocking { var stops=0; var refreshes=0; WearTripInvalidationCoordinator({},{stops++},{refreshes++}).onStop(); assertEquals(1,stops); assertEquals(1,refreshes) }

    @Test fun startFailureDoesNotRetryOrMutateState() = runBlocking {
        var starts = 0; var stops = 0; var reconciles = 0
        val coordinator = WearTripInvalidationCoordinator({ starts++ }, { stops++ }) { reconciles++; error("failure") }
        runCatching { coordinator.onStart() }
        assertEquals(0, starts); assertEquals(0, stops); assertEquals(1, reconciles)
    }

    @Test fun stopFailureDoesNotRetryOrMutateState() = runBlocking {
        var starts = 0; var stops = 0; var reconciles = 0
        val coordinator = WearTripInvalidationCoordinator({ starts++ }, { stops++ }) { reconciles++; error("failure") }
        runCatching { coordinator.onStop() }
        assertEquals(0, starts); assertEquals(1, stops); assertEquals(1, reconciles)
    }

    @Test fun consecutiveInvalidationsSerializeReconciliation() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        var reconciles = 0; var active = 0; var maxActive = 0
        val coordinator = WearTripInvalidationCoordinator({}, {}) {
            reconciles++; active++; maxActive = maxOf(maxActive, active)
            if (reconciles == 1) { firstStarted.complete(Unit); releaseFirst.await() }
            else { secondStarted.complete(Unit); releaseSecond.await() }
            active--
        }
        val start = launch { coordinator.onStart() }
        firstStarted.await()
        val stop = launch { coordinator.onStop() }
        assertEquals(1, reconciles)
        releaseFirst.complete(Unit)
        secondStarted.await()
        assertEquals(1, maxActive)
        releaseSecond.complete(Unit)
        start.join(); stop.join()
        assertEquals(2, reconciles); assertEquals(1, maxActive)
    }
}
