package com.example.sos_segundoplano.domain.signals

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object SpeedPolicy {
    fun metersPerSecondToKilometersPerHour(value: Float?): Float? {
        if (value == null || !value.isFinite() || value < 0f) return null
        return value * 3.6f
    }

    fun resolve(current: LocationSample, previous: LocationSample?): SignalReading<SpeedSample> {
        val direct = current.speedMetersPerSecond
        if (direct != null && direct.isFinite() && direct >= 0f) {
            return SignalReading(
                SignalAvailability.Available,
                SpeedSample(direct, current.timestampMillis, SpeedSource.DirectLocation)
            )
        }

        if (previous == null) {
            return SignalReading(SignalAvailability.Waiting)
        }

        val elapsedSeconds = (current.timestampMillis - previous.timestampMillis) / 1000.0
        if (!elapsedSeconds.isFinite() || elapsedSeconds <= 0.0) {
            return SignalReading(SignalAvailability.Waiting)
        }

        val distanceMeters = distanceMeters(previous, current)
        val speed = distanceMeters / elapsedSeconds
        if (!speed.isFinite() || speed < 0.0) {
            return SignalReading(SignalAvailability.Waiting)
        }

        return SignalReading(
            SignalAvailability.Available,
            SpeedSample(speed.toFloat(), current.timestampMillis, SpeedSource.DerivedLocation)
        )
    }

    private fun distanceMeters(from: LocationSample, to: LocationSample): Double {
        val earthRadiusMeters = 6_371_000.0
        val fromLat = Math.toRadians(from.latitude)
        val toLat = Math.toRadians(to.latitude)
        val deltaLat = Math.toRadians(to.latitude - from.latitude)
        val deltaLon = Math.toRadians(to.longitude - from.longitude)
        val a = sin(deltaLat / 2) * sin(deltaLat / 2) +
            cos(fromLat) * cos(toLat) * sin(deltaLon / 2) * sin(deltaLon / 2)
        val c = 2 * asin(sqrt(a))
        return earthRadiusMeters * c
    }
}
