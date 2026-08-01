package com.example.sos_segundoplano.wear

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

class WearMotionSensorSource(
    context: Context,
    private val sensorType: Int,
    private val onUpdate: (WearSignalAvailability, WearVectorSample?) -> Unit
) {
    private val sensorManager = context.applicationContext.getSystemService(SensorManager::class.java)
    private val sensor = sensorManager.getDefaultSensor(sensorType)
    private var started = false
    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.values.size < 3) return
            onUpdate(
                WearSignalAvailability.Available,
                WearVectorSample(event.values[0], event.values[1], event.values[2], event.timestamp, event.accuracy)
            )
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun start() {
        if (started) return
        val currentSensor = sensor
        if (currentSensor == null) {
            onUpdate(WearSignalAvailability.Unsupported, null)
            return
        }
        if (sensorManager.registerListener(listener, currentSensor, 50_000)) {
            started = true
            onUpdate(WearSignalAvailability.Waiting, null)
        } else {
            onUpdate(WearSignalAvailability.Error, null)
        }
    }

    fun stop() {
        if (!started) return
        sensorManager.unregisterListener(listener)
        started = false
    }
}
