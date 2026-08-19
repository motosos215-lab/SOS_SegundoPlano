package com.example.sos_segundoplano.data.signals

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.example.sos_segundoplano.domain.signals.GpsCalibrationCompletion
import com.example.sos_segundoplano.domain.signals.GpsCalibrationState
import com.example.sos_segundoplano.domain.signals.LocationSample
import com.example.sos_segundoplano.domain.signals.SignalAvailability
import com.example.sos_segundoplano.domain.signals.SignalReading

class AndroidLocationSignalSource(
    context: Context,
    private val store: TripSignalStore
) : SignalSource {
    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(LocationManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var started = false
    private val calibrator = GpsStartupCalibrator()
    private var provider: String? = null

    private val calibrationTimeout = Runnable {
        if (!started) return@Runnable
        val current = store.snapshots.value.gpsCalibration
        if (current is GpsCalibrationState.Calibrating) {
            val primaryLocation = store.snapshots.value.location
            store.updateGpsCalibration(calibrator.onTimeout())
            // The location was already kept as the primary fix during calibration. Re-publish the
            // same reading once the gate opens so preprocessing/speed can start immediately even
            // if Android does not deliver another callback while the motorcycle is stationary.
            if (primaryLocation.availability == SignalAvailability.Available && primaryLocation.sample != null) {
                store.updateLocation(primaryLocation)
            }
        }
    }

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val sample = location.toSample()
            // Update the primary location on every valid Android callback regardless of accuracy.
            // Calibration never hides this fix from trip/SOS consumers.
            val calibrationState = calibrator.onSample(sample)
            store.updateGpsCalibration(calibrationState)
            store.updateLocation(SignalReading(SignalAvailability.Available, sample))
            if (calibrationState is GpsCalibrationState.Ready &&
                calibrationState.completion == GpsCalibrationCompletion.AccurateSamples
            ) {
                mainHandler.removeCallbacks(calibrationTimeout)
            }
        }

        override fun onProviderDisabled(provider: String) {
            mainHandler.removeCallbacks(calibrationTimeout)
            calibrator.reset()
            store.updateGpsCalibration(GpsCalibrationState.Idle)
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
            store.updateGpsCalibration(calibrator.start())
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
            mainHandler.removeCallbacks(calibrationTimeout)
            mainHandler.postDelayed(calibrationTimeout, GpsStartupCalibrator.MAX_STARTUP_CALIBRATION_MILLIS)
        } catch (_: SecurityException) {
            mainHandler.removeCallbacks(calibrationTimeout)
            calibrator.reset()
            store.updateGpsCalibration(GpsCalibrationState.Idle)
            store.updateLocation(SignalReading(SignalAvailability.PermissionMissing))
        } catch (_: IllegalArgumentException) {
            mainHandler.removeCallbacks(calibrationTimeout)
            calibrator.reset()
            store.updateGpsCalibration(GpsCalibrationState.Idle)
            store.updateLocation(SignalReading(SignalAvailability.Unsupported))
        }
    }

    override fun stop() {
        mainHandler.removeCallbacks(calibrationTimeout)
        if (!started) {
            calibrator.reset()
            return
        }
        try {
            locationManager.removeUpdates(listener)
        } catch (_: SecurityException) {
        }
        provider = null
        started = false
        calibrator.reset()
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
        const val MIN_DISTANCE_METERS = 0f
    }
}
