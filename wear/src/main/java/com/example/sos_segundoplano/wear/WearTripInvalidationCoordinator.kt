package com.example.sos_segundoplano.wear

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class WearTripInvalidationCoordinator(
    private val startSignals: suspend () -> Unit,
    private val stopSignals: () -> Unit,
    private val reconcileTripState: suspend () -> Unit,
) {
    private val mutex = Mutex()
    suspend fun onStart() { reconcile(); startSignals() }
    suspend fun onStop() { stopSignals(); reconcile() }
    private suspend fun reconcile() = mutex.withLock { reconcileTripState() }
}
