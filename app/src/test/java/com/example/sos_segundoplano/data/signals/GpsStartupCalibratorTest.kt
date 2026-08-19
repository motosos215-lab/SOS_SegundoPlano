package com.example.sos_segundoplano.data.signals

import com.example.sos_segundoplano.domain.signals.GpsCalibrationCompletion
import com.example.sos_segundoplano.domain.signals.GpsCalibrationState
import com.example.sos_segundoplano.domain.signals.LocationSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsStartupCalibratorTest {
    @Test fun requiresThreeConsecutiveAccurateFixesBeforeReady() {
        val calibrator = GpsStartupCalibrator(targetAccuracyMeters = 20f, requiredAccurateSamples = 3)

        assertTrue(calibrator.start() is GpsCalibrationState.Calibrating)
        assertEquals(1, (calibrator.onSample(location(18f)) as GpsCalibrationState.Calibrating).consecutiveAccurateSamples)
        assertEquals(2, (calibrator.onSample(location(12f)) as GpsCalibrationState.Calibrating).consecutiveAccurateSamples)

        val ready = calibrator.onSample(location(9f)) as GpsCalibrationState.Ready
        assertEquals(9f, ready.achievedAccuracyMeters)
        assertEquals(3, ready.consecutiveAccurateSamples)
        assertEquals(GpsCalibrationCompletion.AccurateSamples, ready.completion)
    }

    @Test fun poorFixResetsTheConsecutiveAccuracyStreak() {
        val calibrator = GpsStartupCalibrator(targetAccuracyMeters = 20f, requiredAccurateSamples = 3)
        calibrator.start()
        calibrator.onSample(location(15f))
        calibrator.onSample(location(14f))

        val reset = calibrator.onSample(location(45f)) as GpsCalibrationState.Calibrating
        assertEquals(0, reset.consecutiveAccurateSamples)
        assertEquals(14f, reset.bestAccuracyMeters)

        assertTrue(calibrator.onSample(location(11f)) is GpsCalibrationState.Calibrating)
        assertTrue(calibrator.onSample(location(10f)) is GpsCalibrationState.Calibrating)
        assertTrue(calibrator.onSample(location(8f)) is GpsCalibrationState.Ready)
    }

    @Test fun accurateReadyStateIsLatchedAfterStartupCalibrationCompletes() {
        val calibrator = GpsStartupCalibrator(targetAccuracyMeters = 20f, requiredAccurateSamples = 2)
        calibrator.start()
        calibrator.onSample(location(12f))
        val ready = calibrator.onSample(location(10f)) as GpsCalibrationState.Ready

        val laterPoorFix = calibrator.onSample(location(80f)) as GpsCalibrationState.Ready
        assertEquals(ready.achievedAccuracyMeters, laterPoorFix.achievedAccuracyMeters)
        assertEquals(GpsCalibrationCompletion.AccurateSamples, laterPoorFix.completion)
    }

    @Test fun timeoutReleasesCalibrationUsingBestAvailableAccuracy() {
        val calibrator = GpsStartupCalibrator(targetAccuracyMeters = 20f, requiredAccurateSamples = 3)
        calibrator.start()
        calibrator.onSample(location(52f))
        calibrator.onSample(location(31f))
        calibrator.onSample(location(27f))

        val ready = calibrator.onTimeout()

        assertEquals(GpsCalibrationCompletion.Timeout, ready.completion)
        assertEquals(27f, ready.achievedAccuracyMeters)
        assertEquals(0, ready.consecutiveAccurateSamples)
    }

    @Test fun timeoutWithoutAnyFixStillReleasesAndFirstLaterFixIsAccepted() {
        val calibrator = GpsStartupCalibrator(targetAccuracyMeters = 20f, requiredAccurateSamples = 3)
        calibrator.start()

        val timeout = calibrator.onTimeout()
        assertEquals(GpsCalibrationCompletion.Timeout, timeout.completion)
        assertNull(timeout.achievedAccuracyMeters)

        val firstFix = calibrator.onSample(location(80f)) as GpsCalibrationState.Ready
        assertEquals(80f, firstFix.achievedAccuracyMeters)
        assertEquals(GpsCalibrationCompletion.Timeout, firstFix.completion)
    }

    @Test fun timeoutReadyCanUpgradeLaterWhenGpsActuallyStabilizes() {
        val calibrator = GpsStartupCalibrator(targetAccuracyMeters = 20f, requiredAccurateSamples = 3)
        calibrator.start()
        calibrator.onSample(location(40f))
        calibrator.onTimeout()

        assertEquals(GpsCalibrationCompletion.Timeout, (calibrator.onSample(location(18f)) as GpsCalibrationState.Ready).completion)
        assertEquals(GpsCalibrationCompletion.Timeout, (calibrator.onSample(location(15f)) as GpsCalibrationState.Ready).completion)
        val upgraded = calibrator.onSample(location(11f)) as GpsCalibrationState.Ready
        assertEquals(GpsCalibrationCompletion.AccurateSamples, upgraded.completion)
        assertEquals(11f, upgraded.achievedAccuracyMeters)
    }

    private fun location(accuracyMeters: Float) = LocationSample(
        latitude = 19.4326,
        longitude = -99.1332,
        accuracyMeters = accuracyMeters,
        timestampMillis = 1_000L,
        provider = "gps",
        isMock = false
    )
}
