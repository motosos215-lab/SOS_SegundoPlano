package com.example.sos_segundoplano.wear

import android.content.Intent
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class MotoSosWearableListenerService : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            WearDataLayerProtocol.PATH_TRIP_START -> {
                val intent = WearSignalForegroundService.createStartIntent(this)
                try {
                    ContextCompat.startForegroundService(this, intent)
                } catch (_: IllegalStateException) {
                    WearSignalForegroundService.publishStartFailure(applicationContext)
                } catch (_: SecurityException) {
                    WearSignalForegroundService.publishStartFailure(applicationContext)
                }
            }
            WearDataLayerProtocol.PATH_TRIP_STOP -> {
                stopService(WearSignalForegroundService.createStopIntent(this))
            }
            WearDataLayerProtocol.PATH_VALIDATION_STATUS -> {
                val status = WearDataLayerProtocol.decodeValidationStatus(messageEvent.data)
                startService(WearSignalForegroundService.createValidationStatusIntent(this, status))
            }
        }
    }
}
