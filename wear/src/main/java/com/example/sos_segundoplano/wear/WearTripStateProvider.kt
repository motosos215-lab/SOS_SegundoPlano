package com.example.sos_segundoplano.wear

import android.content.Context
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Single process owner for the state observed by both the Activity and Data Layer listener. */
internal class WearTripStateDependencies(context: Context) {
    val store = WearTripStateStore()
    val reconciler = WearTripStateReconciler(MobileCompanionGateway(context), store)
    private val reconciliationMutex = Mutex()

    suspend fun reconcileAfterInvalidation() = reconciliationMutex.withLock {
        val result = reconciler.refreshTripState()
        if (BuildConfig.DEBUG) {
            val type = if (result is MobileCompanionResult.Success) "success" else "failure"
            Log.d(LOG_TAG, "event=trip_reconcile_after_invalidation result=$type")
        }
        result
    }

    companion object {
        private const val LOG_TAG = "MotoSOS.WearLink"
    }
}

internal object WearTripStateProvider {
    @Volatile private var dependencies: WearTripStateDependencies? = null

    fun get(context: Context): WearTripStateDependencies = dependencies ?: synchronized(this) {
        dependencies ?: WearTripStateDependencies(context.applicationContext).also { dependencies = it }
    }
}
