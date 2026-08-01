package com.example.sos_segundoplano.data.signals

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.example.sos_segundoplano.domain.signals.SignalAvailability
import com.example.sos_segundoplano.domain.signals.SignalReading
import com.example.sos_segundoplano.domain.signals.Vector3Sample

class AndroidMotionSensorSignalSource(
    context: Context,
    private val sensorType: Int,
    private val store: TripSignalStore
) : SignalSource {
    private val sensorManager = context.applicationContext.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sensorManager.getDefaultSensor(sensorType)
    private var started = false
    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.values.size < 3) return
            val sample = Vector3Sample(
                x = event.values[0],
                y = event.values[1],
                z = event.values[2],
                timestampNanos = event.timestamp,
                accuracy = event.accuracy
            )
            update(SignalReading(SignalAvailability.Available, sample))
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    override fun start() {
        if (started) return
        val currentSensor = sensor
        if (currentSensor == null) {
            update(SignalReading(SignalAvailability.Unsupported))
            return
        }
        val registered = try {
            sensorManager.registerListener(
                listener,
                currentSensor,
                SENSOR_DELAY_MICROS
            )
        } catch (_: RuntimeException) {
            false
        }
        if (registered) {
            started = true
            update(SignalReading(SignalAvailability.Waiting))
        } else {
            update(SignalReading(SignalAvailability.Error("register_failed")))
        }
    }

    override fun stop() {
        if (!started) return
        sensorManager.unregisterListener(listener)
        started = false
    }

    private fun update(reading: SignalReading<Vector3Sample>) {
        if (sensorType == Sensor.TYPE_ACCELEROMETER) {
            store.updateMobileAccelerometer(reading)
        } else {
            store.updateMobileGyroscope(reading)
        }
    }

    private companion object {
        const val SENSOR_DELAY_MICROS = 50_000
    }
}
