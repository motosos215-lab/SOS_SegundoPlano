package com.example.sos_segundoplano.data.signals

import com.example.sos_segundoplano.data.preprocessing.RawSignalEventSink
import com.example.sos_segundoplano.data.preprocessing.RawSignalEventStream
import com.example.sos_segundoplano.domain.preprocessing.ProcessingTimestamp
import com.example.sos_segundoplano.domain.preprocessing.RawSignalEvent
import com.example.sos_segundoplano.domain.preprocessing.RawSignalValue
import com.example.sos_segundoplano.domain.preprocessing.SignalKind
import com.example.sos_segundoplano.domain.preprocessing.SignalSourceId
import com.example.sos_segundoplano.domain.preprocessing.TimeDomain
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
import com.example.sos_segundoplano.domain.preprocessing.toRawVector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

interface TripSignalStore {
    val snapshots: StateFlow<TripSignalSnapshot>
    val rawEvents: RawSignalEventSink
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

class InMemoryTripSignalStore(
    override val rawEvents: RawSignalEventSink = RawSignalEventStream(),
    private val phoneTimeNanos: () -> Long = System::nanoTime,
    private val wallClockMillis: () -> Long = System::currentTimeMillis
) : TripSignalStore {
    private val _snapshots = MutableStateFlow(TripSignalSnapshot())
    override val snapshots: StateFlow<TripSignalSnapshot> = _snapshots
    private var previousLocation: LocationSample? = null
    private val sequence = AtomicLong(0L)

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
        emitLocation(reading)
        emitSpeed(speed)
    }

    @Synchronized
    override fun updateMobileAccelerometer(reading: SignalReading<Vector3Sample>) {
        mutate { it.copy(mobileAccelerometer = reading) }
        emitVector(SignalSourceId.Phone, SignalKind.Accelerometer, reading, TimeDomain.PhoneElapsedRealtime)
    }

    @Synchronized
    override fun updateMobileGyroscope(reading: SignalReading<Vector3Sample>) {
        mutate { it.copy(mobileGyroscope = reading) }
        emitVector(SignalSourceId.Phone, SignalKind.Gyroscope, reading, TimeDomain.PhoneElapsedRealtime)
    }

    @Synchronized
    override fun updateSpeed(reading: SignalReading<SpeedSample>) {
        mutate { it.copy(speed = reading) }
        emitSpeed(reading)
    }

    @Synchronized
    override fun updatePhoneBattery(reading: SignalReading<BatterySample>) {
        mutate { it.copy(phoneBattery = reading) }
        emit(
            SignalSourceId.Phone,
            SignalKind.Battery,
            reading.availability,
            reading.sample?.let { RawSignalValue.Battery(it) },
            phoneTimeNanos(),
            reading.sample?.timestampMillis?.let { it * NANOS_PER_MILLI },
            TimeDomain.PhoneWallClock
        )
    }

    @Synchronized
    override fun updateConnectivity(reading: SignalReading<ConnectivitySample>) {
        mutate { it.copy(connectivity = reading) }
        emit(
            SignalSourceId.Phone,
            SignalKind.Connectivity,
            reading.availability,
            reading.sample?.let { RawSignalValue.Connectivity(it) },
            phoneTimeNanos(),
            reading.sample?.timestampMillis?.let { it * NANOS_PER_MILLI },
            TimeDomain.PhoneWallClock
        )
    }

    @Synchronized
    override fun updateWearable(sample: WearableSample) {
        mutate { it.copy(wearable = sample) }
        val receivedAt = phoneTimeNanos()
        sample.accelerometer?.let { emitWearVector(SignalKind.Accelerometer, it, receivedAt) }
        sample.gyroscope?.let { emitWearVector(SignalKind.Gyroscope, it, receivedAt) }
        val heartRate = sample.heartRateBpm
        if (heartRate != null && heartRate.isFinite() && heartRate > 0.0) {
            emit(SignalSourceId.Wear, SignalKind.HeartRate, SignalAvailability.Available, RawSignalValue.Scalar(heartRate), receivedAt, null, TimeDomain.WearElapsedRealtime, receivedAt)
        }
        emit(SignalSourceId.Wear, SignalKind.WearableStatus, SignalAvailability.Available, RawSignalValue.WearStatus(sample.status), receivedAt, sample.lastUpdatedMillis.takeIf { it > 0L }?.let { it * NANOS_PER_MILLI }, TimeDomain.PhoneWallClock, receivedAt)
    }

    @Synchronized
    override fun reset() {
        previousLocation = null
        _snapshots.value = TripSignalSnapshot(captureState = CaptureState.Stopped)
    }

    private fun mutate(block: (TripSignalSnapshot) -> TripSignalSnapshot) {
        val now = wallClockMillis()
        _snapshots.update { block(it).copy(lastUpdatedMillis = now) }
    }

    private fun emitLocation(reading: SignalReading<LocationSample>) {
        emit(
            SignalSourceId.Phone,
            SignalKind.Location,
            reading.availability,
            reading.sample?.let { RawSignalValue.Location(it) },
            phoneTimeNanos(),
            reading.sample?.timestampMillis?.let { it * NANOS_PER_MILLI },
            TimeDomain.PhoneWallClock
        )
    }

    private fun emitSpeed(reading: SignalReading<SpeedSample>) {
        emit(
            SignalSourceId.Phone,
            SignalKind.Speed,
            reading.availability,
            reading.sample?.let { RawSignalValue.Scalar(it.metersPerSecond.toDouble()) },
            phoneTimeNanos(),
            reading.sample?.timestampMillis?.let { it * NANOS_PER_MILLI },
            TimeDomain.PhoneWallClock
        )
    }

    private fun emitVector(source: SignalSourceId, kind: SignalKind, reading: SignalReading<Vector3Sample>, domain: TimeDomain) {
        val sample = reading.sample
        val phoneTime = if (source == SignalSourceId.Phone && sample != null) sample.timestampNanos else phoneTimeNanos()
        emit(source, kind, reading.availability, sample?.toRawVector(), phoneTime, sample?.timestampNanos, domain)
    }

    private fun emitWearVector(kind: SignalKind, reading: SignalReading<Vector3Sample>, receivedAt: Long) {
        emit(
            SignalSourceId.Wear,
            kind,
            reading.availability,
            reading.sample?.toRawVector(),
            receivedAt,
            reading.sample?.timestampNanos,
            TimeDomain.WearElapsedRealtime,
            receivedAt
        )
    }

    private fun emit(
        source: SignalSourceId,
        kind: SignalKind,
        availability: SignalAvailability,
        value: RawSignalValue?,
        phoneTime: Long,
        originalTime: Long?,
        originalDomain: TimeDomain,
        receivedAt: Long? = null
    ) {
        rawEvents.tryEmit(
            RawSignalEvent(
                sourceId = source,
                signalKind = kind,
                availability = availability,
                value = value,
                timestamp = ProcessingTimestamp(
                    phoneTimeNanos = phoneTime,
                    originalTimestampNanos = originalTime,
                    originalTimeDomain = originalDomain,
                    receivedAtPhoneNanos = receivedAt
                ),
                sequenceId = sequence.incrementAndGet()
            )
        )
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

object TripSignalStoreProvider {
    val store: TripSignalStore = InMemoryTripSignalStore()
}
