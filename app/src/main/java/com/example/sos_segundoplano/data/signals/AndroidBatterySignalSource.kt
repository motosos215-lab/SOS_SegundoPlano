package com.example.sos_segundoplano.data.signals

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.example.sos_segundoplano.domain.signals.BatteryPolicy
import com.example.sos_segundoplano.domain.signals.BatterySample
import com.example.sos_segundoplano.domain.signals.SignalAvailability
import com.example.sos_segundoplano.domain.signals.SignalReading

class AndroidBatterySignalSource(
    context: Context,
    private val store: TripSignalStore
) : SignalSource {
    private val appContext = context.applicationContext
    private var started = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateFromIntent(intent)
        }
    }

    override fun start() {
        if (started) return
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        updateFromIntent(appContext.registerReceiver(receiver, filter))
        started = true
    }

    override fun stop() {
        if (!started) return
        try {
            appContext.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
        }
        started = false
    }

    private fun updateFromIntent(intent: Intent?) {
        if (intent == null) {
            store.updatePhoneBattery(SignalReading(SignalAvailability.Waiting))
            return
        }
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percentage = BatteryPolicy.percentage(level, scale)
        if (percentage == null) {
            store.updatePhoneBattery(SignalReading(SignalAvailability.Waiting))
            return
        }
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        store.updatePhoneBattery(
            SignalReading(
                SignalAvailability.Available,
                BatterySample(percentage, charging, System.currentTimeMillis())
            )
        )
    }
}
