package com.example.sos_segundoplano.data.signals

import android.content.Context
import android.hardware.Sensor
import com.example.sos_segundoplano.data.preprocessing.SignalPreprocessingCoordinator
import com.example.sos_segundoplano.domain.signals.CaptureState

class TripSignalCaptureCoordinator(
    private val store: TripSignalStore,
    private val sources: List<SignalSource>,
    private val preprocessingCoordinator: SignalPreprocessingCoordinator? = null
) {
    constructor(
        context: Context,
        store: TripSignalStore = TripSignalStoreProvider.store
    ) : this(
        store,
        listOf(
        AndroidLocationSignalSource(context, store),
        AndroidMotionSensorSignalSource(context, Sensor.TYPE_ACCELEROMETER, store),
        AndroidMotionSensorSignalSource(context, Sensor.TYPE_GYROSCOPE, store),
        AndroidBatterySignalSource(context, store),
        AndroidConnectivitySignalSource(context, store),
        MobileWearableSignalSource(context, store)
        ),
        SignalPreprocessingCoordinator(store.rawEvents)
    )

    private var started = false

    fun start() {
        if (started) return
        started = true
        store.setCaptureState(CaptureState.Starting)
        preprocessingCoordinator?.start()
        sources.forEach { it.start() }
        store.setCaptureState(CaptureState.Active)
    }

    fun stop() {
        if (!started) return
        sources.asReversed().forEach { it.stop() }
        preprocessingCoordinator?.stop()
        started = false
        store.reset()
    }
}
