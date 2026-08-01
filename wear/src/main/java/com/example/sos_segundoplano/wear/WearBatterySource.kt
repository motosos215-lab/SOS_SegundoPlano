package com.example.sos_segundoplano.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

class WearBatterySource(
    private val context: Context,
    private val onUpdate: (Int?) -> Unit
) {
    private val appContext = context.applicationContext
    private var started = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = publish(intent)
    }

    fun start() {
        if (started) return
        publish(appContext.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
        started = true
    }

    fun stop() {
        if (!started) return
        try {
            appContext.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
        }
        started = false
    }

    private fun publish(intent: Intent?) {
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percentage = if (level >= 0 && scale > 0) (level * 100f / scale).toInt() else null
        onUpdate(percentage?.takeIf { it in 0..100 })
    }
}
