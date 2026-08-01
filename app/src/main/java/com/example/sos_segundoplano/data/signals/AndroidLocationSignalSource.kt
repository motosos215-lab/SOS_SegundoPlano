package com.example.sos_segundoplano.data.signals

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import com.example.sos_segundoplano.domain.signals.LocationSample
import com.example.sos_segundoplano.domain.signals.SignalAvailability
import com.example.sos_segundoplano.domain.signals.SignalReading

class AndroidLocationSignalSource(
    context: Context,
    private val store: TripSignalStore
) : SignalSource {
    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(LocationManager::class.java)
    private var started = false
    private var provider: String? = null
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            store.updateLocation(SignalReading(SignalAvailability.Available, location.toSample()))
        }

        override fun onProviderDisabled(provider: String) {
            store.updateLocation(SignalReading(SignalAvailability.Disabled))
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    override fun start() {
        if (started) return
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            store.updateLocation(SignalReading(SignalAvailability.PermissionMissing))
            return
        }

        val selectedProvider = selectProvider()
        if (selectedProvider == null) {
            store.updateLocation(SignalReading(SignalAvailability.Unsupported))
            return
        }
        if (!locationManager.isProviderEnabled(selectedProvider)) {
            store.updateLocation(SignalReading(SignalAvailability.Disabled))
            return
        }

        try {
            locationManager.requestLocationUpdates(
                selectedProvider,
                MIN_TIME_MILLIS,
                MIN_DISTANCE_METERS,
                listener,
                Looper.getMainLooper()
            )
            provider = selectedProvider
            started = true
            store.updateLocation(SignalReading(SignalAvailability.Waiting))
        } catch (_: SecurityException) {
            store.updateLocation(SignalReading(SignalAvailability.PermissionMissing))
        } catch (_: IllegalArgumentException) {
            store.updateLocation(SignalReading(SignalAvailability.Unsupported))
        }
    }

    override fun stop() {
        if (!started) return
        try {
            locationManager.removeUpdates(listener)
        } catch (_: SecurityException) {
        }
        provider = null
        started = false
    }

    private fun selectProvider(): String? {
        val providers = locationManager.getProviders(false)
        return when {
            LocationManager.GPS_PROVIDER in providers -> LocationManager.GPS_PROVIDER
            LocationManager.NETWORK_PROVIDER in providers -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
    }

    private fun Location.toSample(): LocationSample = LocationSample(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracy,
        altitudeMeters = if (hasAltitude()) altitude else null,
        bearingDegrees = if (hasBearing()) bearing else null,
        speedMetersPerSecond = if (hasSpeed() && speed.isFinite() && speed >= 0f) speed else null,
        timestampMillis = time,
        provider = provider ?: "unknown",
        isMock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) isMock else isFromMockProvider
    )

    private companion object {
        const val MIN_TIME_MILLIS = 1_000L
        const val MIN_DISTANCE_METERS = 1f
    }
}
