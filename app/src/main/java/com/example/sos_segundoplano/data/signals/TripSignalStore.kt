package com.example.sos_segundoplano.data.signals

import com.example.sos_segundoplano.domain.signals.BatterySample
import com.example.sos_segundoplano.domain.signals.CaptureState
import com.example.sos_segundoplano.domain.signals.ConnectivitySample
import com.example.sos_segundoplano.domain.signals.LocationSample
import com.example.sos_segundoplano.domain.signals.SignalAvailability
import com.example.sos_segundoplano.domain.signals.SignalReading
import com.example.sos_segundoplano.domain.signals.SpeedPolicy
import com.example.sos_segundoplano.domain.signals.SpeedSample
import com.example.sos_segundoplano.domain.signals.TripSignalSnapshot
import com.example.sos_segundoplano.domain.signals.Vector3Sample
import com.example.sos_segundoplano.domain.signals.WearableSample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

interface TripSignalStore {
    val snapshots: StateFlow<TripSignalSnapshot>
    fun setCaptureState(state: CaptureState)
    fun updateLocation(reading: SignalReading<LocationSample>)
    fun updateMobileAccelerometer(reading: SignalReading<Vector3Sample>)
    fun updateMobileGyroscope(reading: SignalReading<Vector3Sample>)
    fun updateSpeed(reading: SignalReading<SpeedSample>)
    fun updatePhoneBattery(reading: SignalReading<BatterySample>)
    fun updateConnectivity(reading: SignalReading<ConnectivitySample>)
    fun updateWearable(sample: WearableSample)
    fun reset()
}

class InMemoryTripSignalStore : TripSignalStore {
    private val _snapshots = MutableStateFlow(TripSignalSnapshot())
    override val snapshots: StateFlow<TripSignalSnapshot> = _snapshots
    private var previousLocation: LocationSample? = null

    @Synchronized
    override fun setCaptureState(state: CaptureState) = mutate { it.copy(captureState = state) }

    @Synchronized
    override fun updateLocation(reading: SignalReading<LocationSample>) {
        val currentLocation = reading.sample
        val speed = if (reading.availability == SignalAvailability.Available && currentLocation != null) {
            SpeedPolicy.resolve(currentLocation, previousLocation)
        } else {
            SignalReading(SignalAvailability.Waiting)
        }
        previousLocation = currentLocation ?: previousLocation
        mutate { it.copy(location = reading, speed = speed) }
    }

    @Synchronized
    override fun updateMobileAccelerometer(reading: SignalReading<Vector3Sample>) =
        mutate { it.copy(mobileAccelerometer = reading) }

    @Synchronized
    override fun updateMobileGyroscope(reading: SignalReading<Vector3Sample>) =
        mutate { it.copy(mobileGyroscope = reading) }

    @Synchronized
    override fun updateSpeed(reading: SignalReading<SpeedSample>) = mutate { it.copy(speed = reading) }

    @Synchronized
    override fun updatePhoneBattery(reading: SignalReading<BatterySample>) =
        mutate { it.copy(phoneBattery = reading) }

    @Synchronized
    override fun updateConnectivity(reading: SignalReading<ConnectivitySample>) =
        mutate { it.copy(connectivity = reading) }

    @Synchronized
    override fun updateWearable(sample: WearableSample) = mutate { it.copy(wearable = sample) }

    @Synchronized
    override fun reset() {
        previousLocation = null
        _snapshots.value = TripSignalSnapshot(captureState = CaptureState.Stopped)
    }

    private fun mutate(block: (TripSignalSnapshot) -> TripSignalSnapshot) {
        val now = System.currentTimeMillis()
        _snapshots.update { block(it).copy(lastUpdatedMillis = now) }
    }
}

object TripSignalStoreProvider {
    val store: TripSignalStore = InMemoryTripSignalStore()
}
