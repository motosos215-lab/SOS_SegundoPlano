package com.example.sos_segundoplano.data.signals

import com.example.sos_segundoplano.domain.signals.GpsCalibrationCompletion
import com.example.sos_segundoplano.domain.signals.GpsCalibrationState
import com.example.sos_segundoplano.domain.signals.LocationSample
import kotlin.math.min

/**
 * Startup-only GPS quality gate.
 *
 * The first valid location is always kept by TripSignalStore and remains available to trip/SOS
 * consumers regardless of horizontal accuracy. Calibration only controls when GPS-derived speed,
 * risk preprocessing and normal durable route recording may trust the stream.
 *
 * Preferred completion: several consecutive fixes at or below [targetAccuracyMeters].
 * Failsafe completion: [onTimeout] after 20 s in AndroidLocationSignalSource so a slow GPS never
 * blocks route recording for minutes. A timeout-completed calibration can later upgrade itself to
 * AccurateSamples if the GPS obtains the requested streak during the same trip.
 */
class GpsStartupCalibrator(
    private val targetAccuracyMeters: Float = TARGET_ACCURACY_METERS,
    private val requiredAccurateSamples: Int = REQUIRED_ACCURATE_SAMPLES
) {
    init {
        require(targetAccuracyMeters.isFinite() && targetAccuracyMeters > 0f)
        require(requiredAccurateSamples > 0)
    }

    private var ready = false
    private var completion = GpsCalibrationCompletion.AccurateSamples
    private var consecutiveAccurateSamples = 0
    private var bestAccuracyMeters: Float? = null
    private var achievedAccuracyMeters: Float? = null

    fun start(): GpsCalibrationState {
        reset()
        return calibratingState(currentAccuracyMeters = null)
    }

    fun onSample(sample: LocationSample): GpsCalibrationState {
        val accuracy = sample.accuracyMeters.takeIf { it.isFinite() && it >= 0f }
        if (accuracy == null) {
            consecutiveAccurateSamples = 0
            return if (ready) readyState() else calibratingState(currentAccuracyMeters = null)
        }

        bestAccuracyMeters = bestAccuracyMeters?.let { min(it, accuracy) } ?: accuracy
        if (accuracy <= targetAccuracyMeters) {
            consecutiveAccurateSamples++
        } else {
            consecutiveAccurateSamples = 0
        }

        if (consecutiveAccurateSamples >= requiredAccurateSamples) {
            ready = true
            completion = GpsCalibrationCompletion.AccurateSamples
            achievedAccuracyMeters = accuracy
            return readyState()
        }

        if (ready) {
            // Timeout may have released the stream already. Keep improving the recorded achieved
            // accuracy without re-blocking the trip while we wait for a true high-quality streak.
            achievedAccuracyMeters = bestAccuracyMeters
            return readyState()
        }

        return calibratingState(currentAccuracyMeters = accuracy)
    }

    /**
     * Releases startup calibration even if the three preferred fixes were not obtained.
     * A null achieved accuracy means the timeout elapsed before the first valid GPS fix; the next
     * valid location is still accepted immediately and updates this Ready state.
     */
    fun onTimeout(): GpsCalibrationState.Ready {
        if (!ready) {
            ready = true
            completion = GpsCalibrationCompletion.Timeout
            achievedAccuracyMeters = bestAccuracyMeters
        }
        return readyState()
    }

    fun reset() {
        ready = false
        completion = GpsCalibrationCompletion.AccurateSamples
        consecutiveAccurateSamples = 0
        bestAccuracyMeters = null
        achievedAccuracyMeters = null
    }

    private fun readyState(): GpsCalibrationState.Ready =
        GpsCalibrationState.Ready(
            achievedAccuracyMeters = achievedAccuracyMeters,
            consecutiveAccurateSamples = consecutiveAccurateSamples,
            targetAccuracyMeters = targetAccuracyMeters,
            completion = completion
        )

    private fun calibratingState(currentAccuracyMeters: Float?): GpsCalibrationState.Calibrating =
        GpsCalibrationState.Calibrating(
            currentAccuracyMeters = currentAccuracyMeters,
            bestAccuracyMeters = bestAccuracyMeters,
            consecutiveAccurateSamples = consecutiveAccurateSamples,
            requiredAccurateSamples = requiredAccurateSamples,
            targetAccuracyMeters = targetAccuracyMeters
        )

    companion object {
        const val TARGET_ACCURACY_METERS = 20f
        const val REQUIRED_ACCURATE_SAMPLES = 3
        const val MAX_STARTUP_CALIBRATION_MILLIS = 20_000L
    }
}
