package com.example.sos_segundoplano.data.route

import android.util.Log
import com.example.sos_segundoplano.data.remote.trip.RemoteTripSessionStore
import com.example.sos_segundoplano.data.signals.TripSignalStore
import com.example.sos_segundoplano.data.trip.TripSessionStore
import com.example.sos_segundoplano.domain.auth.SessionState
import com.example.sos_segundoplano.domain.auth.UserRole
import com.example.sos_segundoplano.domain.model.TripSessionState
import com.example.sos_segundoplano.domain.repository.AuthRepository
import com.example.sos_segundoplano.domain.signals.GpsCalibrationState
import com.example.sos_segundoplano.domain.signals.LocationSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class TripRouteRecorder(
    private val authRepository: AuthRepository,
    private val signalStore: TripSignalStore,
    private val tripSessionStore: TripSessionStore,
    private val remoteTripSessionStore: RemoteTripSessionStore,
    private val dao: TripRoutePointDao,
    private val scheduler: TripRouteWorkScheduler
) {
    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private val sequenceCache = ConcurrentHashMap<String, Long>()
    private val startupBuffer = StartupRoutePointBuffer()
    private var pointsSinceSchedule = 0

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        val nextScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = nextScope
        job = nextScope.launch {
            signalStore.snapshots
                .map { RouteObservation(it.location.sample, it.gpsCalibration) }
                .distinctUntilChanged()
                .collect(::handleObservation)
        }
        Log.d(TAG, "route recorder started")
    }

    @Synchronized
    fun stopAndScheduleFinalSync() {
        job?.cancel()
        job = null
        scope?.cancel()
        scope = null
        pointsSinceSchedule = 0
        startupBuffer.clear()
        scheduler.schedule()
        Log.d(TAG, "route recorder stopped; final sync scheduled")
    }

    private suspend fun handleObservation(observation: RouteObservation) {
        val sample = observation.sample
        if (observation.calibration is GpsCalibrationState.Calibrating) {
            if (sample != null && sample.isValidRoutePoint()) startupBuffer.add(sample)
            return
        }

        // Calibration ended either with 3 good fixes or the 20 s failsafe. Persist the startup
        // samples first so history starts where the ride actually started instead of 20 s later.
        val buffered = startupBuffer.drain()
        buffered.forEach { persistReady(it) }
        if (sample != null && sample.isValidRoutePoint() && buffered.none { it.sameFixAs(sample) }) {
            persistReady(sample)
        }
    }

    private suspend fun persistReady(sample: LocationSample) {
        if (!sample.isValidRoutePoint()) return
        val session = authRepository.observeSession().value
        val user = when (session) {
            is SessionState.Authenticated -> session.user
            is SessionState.Refreshing -> session.user
            else -> null
        }
        if (user?.role != UserRole.Rider || user.id.isBlank()) return
        val trip = tripSessionStore.states.value as? TripSessionState.Active ?: return
        val remoteTripId = remoteTripSessionStore.remoteTripId.value?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val remoteKey = remoteTripSessionStore.tripSessionKey.value
        if (remoteKey != null && remoteKey != trip.tripSessionKey) return

        val sequenceKey = "${user.id}|$remoteTripId"
        val current = sequenceCache[sequenceKey] ?: maxOf(
            dao.sequenceCheckpoint(user.id, remoteTripId) ?: 0L,
            dao.maxSequence(user.id, remoteTripId)
        )
        val next = current + 1L
        // Persist the sequence before the queue row. A crash between these writes may leave a
        // harmless gap, but it can never reuse an already-sent sequence after process death.
        dao.saveSequenceCheckpoint(
            TripRouteSequenceCheckpointEntity(
                ownerUserId = user.id,
                remoteTripId = remoteTripId,
                lastSequence = next
            )
        )
        sequenceCache[sequenceKey] = next
        val entity = TripRoutePointEntity(
            clientRoutePointId = UUID.randomUUID().toString(),
            ownerUserId = user.id,
            tripSessionKey = trip.tripSessionKey,
            remoteTripId = remoteTripId,
            sequence = next,
            recordedAtUtc = Instant.ofEpochMilli(sample.timestampMillis).toString(),
            latitude = sample.latitude,
            longitude = sample.longitude,
            accuracyMeters = sample.accuracyMeters.toDouble(),
            speedMetersPerSecond = sample.speedMetersPerSecond?.toDouble(),
            bearingDegrees = sample.bearingDegrees?.toDouble(),
            createdAtEpochMillis = System.currentTimeMillis()
        )
        if (dao.insert(entity) != -1L) {
            pointsSinceSchedule++
            if (pointsSinceSchedule >= SYNC_EVERY_POINTS) {
                pointsSinceSchedule = 0
                scheduler.schedule()
            }
        }
    }

    private fun LocationSample.isValidRoutePoint(): Boolean =
        latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
            accuracyMeters.isFinite() && accuracyMeters >= 0f &&
            timestampMillis >= 0L

    private fun LocationSample.sameFixAs(other: LocationSample): Boolean =
        timestampMillis == other.timestampMillis && latitude == other.latitude && longitude == other.longitude

    private data class RouteObservation(
        val sample: LocationSample?,
        val calibration: GpsCalibrationState
    )

    companion object {
        private const val SYNC_EVERY_POINTS = 10
        private const val TAG = "MotoSOS.TripRoute"
    }
}
