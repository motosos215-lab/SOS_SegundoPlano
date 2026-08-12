package com.example.sos_segundoplano.data.remote.incident

import com.example.sos_segundoplano.data.signals.TripSignalStore
import com.example.sos_segundoplano.domain.rules.RuleEngineConfig
import com.example.sos_segundoplano.domain.signals.LocationSample
import com.example.sos_segundoplano.domain.signals.SignalAvailability

fun interface ManualSosLocationProvider {
    fun currentRealLocation(): LocationSample?
}

class TripSignalManualSosLocationProvider(
    private val store: TripSignalStore,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val maxAgeMillis: Long = RuleEngineConfig().gpsMaxAgeNanos / NANOS_PER_MILLI
) : ManualSosLocationProvider {
    override fun currentRealLocation(): LocationSample? {
        val reading = store.snapshots.value.location
        if (reading.availability != SignalAvailability.Available) return null
        val sample = reading.sample ?: return null
        val ageMillis = nowEpochMillis() - sample.timestampMillis
        return sample.takeIf {
            ageMillis in 0..maxAgeMillis &&
                !it.isMock &&
                it.latitude.isFinite() &&
                it.longitude.isFinite() &&
                it.latitude in MIN_LATITUDE..MAX_LATITUDE &&
                it.longitude in MIN_LONGITUDE..MAX_LONGITUDE &&
                !(it.latitude == 0.0 && it.longitude == 0.0)
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val MIN_LATITUDE = -90.0
        const val MAX_LATITUDE = 90.0
        const val MIN_LONGITUDE = -180.0
        const val MAX_LONGITUDE = 180.0
    }
}
