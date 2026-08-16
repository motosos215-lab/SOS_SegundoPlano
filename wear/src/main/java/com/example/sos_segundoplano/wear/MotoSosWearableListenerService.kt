package com.example.sos_segundoplano.wear

import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MotoSosWearableListenerService : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            WearDataLayerProtocol.PATH_TRIP_START -> {
                dispatchInvalidation("start") { it.onStart() }
            }
            WearDataLayerProtocol.PATH_TRIP_STOP -> {
                dispatchInvalidation("stop") { it.onStop() }
            }
            WearDataLayerProtocol.PATH_VALIDATION_STATUS -> {
                if (!WearValidationStatusReceiver.handle(messageEvent.data)) return
                startService(WearSignalForegroundService.createValidationStatusIntent(this))
            }
        }
    }

    private fun dispatchInvalidation(type: String, action: suspend (WearTripInvalidationCoordinator) -> Unit) {
        if (BuildConfig.DEBUG) Log.d(LOG_TAG, "event=trip_invalidation_received type=$type")
        CoroutineScope(Dispatchers.IO).launch {
            action(
                WearTripInvalidationCoordinator(
                    startSignals = ::startSignals,
                    stopSignals = ::stopSignals,
                    reconcileTripState = { WearTripStateProvider.get(applicationContext).reconcileAfterInvalidation() }
                )
            )
        }
    }

    private fun startSignals() {
        try { ContextCompat.startForegroundService(this, WearSignalForegroundService.createStartIntent(this)) }
        catch (_: IllegalStateException) { WearSignalForegroundService.publishStartFailure(applicationContext) }
        catch (_: SecurityException) { WearSignalForegroundService.publishStartFailure(applicationContext) }
    }

    private fun stopSignals() { stopService(WearSignalForegroundService.createStopIntent(this)) }

    private companion object {
        const val LOG_TAG = "MotoSOS.WearLink"
    }
}
