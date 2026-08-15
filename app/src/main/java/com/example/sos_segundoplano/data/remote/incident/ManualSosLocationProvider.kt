package com.example.sos_segundoplano.data.remote.incident

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
import com.example.sos_segundoplano.data.signals.TripSignalStore
import com.example.sos_segundoplano.domain.rules.RuleEngineConfig
import com.example.sos_segundoplano.domain.signals.LocationSample
import com.example.sos_segundoplano.domain.signals.SignalAvailability
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import java.util.concurrent.atomic.AtomicBoolean

fun interface ManualSosLocationProvider {
    suspend fun currentRealLocation(): LocationSample?
}

class TripSignalManualSosLocationProvider(
    private val store: TripSignalStore,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val maxAgeMillis: Long = RuleEngineConfig().gpsMaxAgeNanos / NANOS_PER_MILLI,
    private val currentLocationProvider: CurrentManualSosLocationProvider = UnavailableCurrentManualSosLocationProvider,
    private val currentLocationTimeoutMillis: Long = CURRENT_LOCATION_TIMEOUT_MILLIS
) : ManualSosLocationProvider {
    override suspend fun currentRealLocation(): LocationSample? {
        val reading = store.snapshots.value.location
        validLocation(reading.sample, reading.availability)?.let { return it }
        return validLocation(
            withTimeoutOrNull(currentLocationTimeoutMillis) { currentLocationProvider.currentLocation() },
            SignalAvailability.Available
        )
    }

    private fun validLocation(
        sample: LocationSample?,
        availability: SignalAvailability
    ): LocationSample? {
        if (availability != SignalAvailability.Available) return null
        val candidate = sample ?: return null
        val ageMillis = nowEpochMillis() - candidate.timestampMillis
        return candidate.takeIf { isValidRealLocation(it, ageMillis, maxAgeMillis) }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val CURRENT_LOCATION_TIMEOUT_MILLIS = 10_000L
    }
}

fun interface CurrentManualSosLocationProvider {
    suspend fun currentLocation(): LocationSample?
}

class AndroidCurrentManualSosLocationProvider(
    context: Context,
    private val timeoutMillis: Long = CURRENT_LOCATION_TIMEOUT_MILLIS
) : CurrentManualSosLocationProvider {
    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(LocationManager::class.java)

    override suspend fun currentLocation(): LocationSample? = withTimeoutOrNull(timeoutMillis) {
        suspendCancellableCoroutine { continuation ->
            if (!hasLocationPermission()) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            val providers = selectProviders()
            if (providers.isEmpty()) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            val listeners = mutableListOf<LocationListener>()
            fun removeAllListeners() = listeners.forEach { listener ->
                runCatching { locationManager.removeUpdates(listener) }
            }
            val completed = AtomicBoolean(false)
            fun complete(location: LocationSample) {
                if (completed.compareAndSet(false, true) && continuation.isActive) {
                    removeAllListeners()
                    continuation.resume(location)
                }
            }
            continuation.invokeOnCancellation {
                completed.compareAndSet(false, true)
                removeAllListeners()
            }
            var registrations = 0
            providers.forEach { provider ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        val sample = location.toSample(provider)
                        if (isValidRealLocation(sample, System.currentTimeMillis() - sample.timestampMillis, MAX_LOCATION_AGE_MILLIS)) {
                            complete(sample)
                        }
                    }
                    override fun onProviderDisabled(provider: String) = Unit
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                }
                listeners += listener
                try {
                    locationManager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                    registrations++
                } catch (_: SecurityException) {
                } catch (_: IllegalArgumentException) {
                }
            }
            if (registrations == 0 && completed.compareAndSet(false, true) && continuation.isActive) {
                removeAllListeners()
                continuation.resume(null)
            }
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun selectProviders(): List<String> {
        val providers = locationManager.getProviders(true)
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { it in providers }
    }

    private fun Location.toSample(provider: String): LocationSample = LocationSample(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracy,
        altitudeMeters = if (hasAltitude()) altitude else null,
        bearingDegrees = if (hasBearing()) bearing else null,
        speedMetersPerSecond = if (hasSpeed() && speed.isFinite() && speed >= 0f) speed else null,
        timestampMillis = time,
        provider = provider,
        isMock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) isMock else isFromMockProvider
    )

    private companion object {
        const val CURRENT_LOCATION_TIMEOUT_MILLIS = 10_000L
        val MAX_LOCATION_AGE_MILLIS = RuleEngineConfig().gpsMaxAgeNanos / 1_000_000L
    }
}

internal fun isValidRealLocation(sample: LocationSample, ageMillis: Long, maxAgeMillis: Long): Boolean =
    ageMillis in 0..maxAgeMillis &&
        !sample.isMock &&
        sample.latitude.isFinite() &&
        sample.longitude.isFinite() &&
        sample.accuracyMeters.isFinite() && sample.accuracyMeters >= 0f &&
        sample.latitude in -90.0..90.0 &&
        sample.longitude in -180.0..180.0 &&
        !(sample.latitude == 0.0 && sample.longitude == 0.0)

private object UnavailableCurrentManualSosLocationProvider : CurrentManualSosLocationProvider {
    override suspend fun currentLocation(): LocationSample? = null
}
