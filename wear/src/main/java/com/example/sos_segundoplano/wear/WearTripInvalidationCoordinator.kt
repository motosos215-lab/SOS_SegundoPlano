package com.example.sos_segundoplano.wear

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class WearTripInvalidationCoordinator(
    private val startSignals: () -> Unit,
    private val stopSignals: () -> Unit,
    private val reconcileTripState: suspend () -> Unit,
) {
    private val mutex = Mutex()
    suspend fun onStart() { startSignals(); reconcile() }
    suspend fun onStop() { stopSignals(); reconcile() }
    private suspend fun reconcile() = mutex.withLock { reconcileTripState() }
}
