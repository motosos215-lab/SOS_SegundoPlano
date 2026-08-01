package com.example.sos_segundoplano.domain.preprocessing

import com.example.sos_segundoplano.domain.signals.BatterySample
import com.example.sos_segundoplano.domain.signals.ConnectivitySample
import com.example.sos_segundoplano.domain.signals.LocationSample
import com.example.sos_segundoplano.domain.signals.NetworkTransport
import com.example.sos_segundoplano.domain.signals.SignalAvailability
import com.example.sos_segundoplano.domain.signals.WearableStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreprocessingAcceptanceTest {
    @Test fun synchronizerRejectsSamplesOutsideTolerance() {
        val frames = SignalSynchronizer(PreprocessingConfig(synchronizationToleranceNanos = 5L)).synchronize(
            listOf(filteredVector(SignalSourceId.Phone, 10L), filteredVector(SignalSourceId.Wear, 100L))
        )

        assertFalse(frames.single().withinTolerance)
        assertNull(frames.single().wearSample)
    }

    @Test fun repeatedWindowGenerationDoesNotEmitSameWindowTwice() {
        val generator = AnalysisWindowGenerator(PreprocessingConfig(windowDurationNanos = 10L, windowStepNanos = 10L))
        val samples = listOf(0L, 10L, 20L).map { filteredVector(SignalSourceId.Phone, it) }

        val first = generator.windows(1L, samples, false, 0L)
        val second = generator.windows(1L, samples, false, 0L)

        assertTrue(first.isNotEmpty())
        assertTrue(second.isEmpty())
    }

    @Test fun partialWindowCanBeDisabledByConfiguration() {
        val pipeline = PreprocessingPipeline(PreprocessingConfig(windowDurationNanos = 100L, emitPartialWindowOnStop = false))
        pipeline.accept(1L, vectorEvent(SignalSourceId.Phone, 1L), 0L)

        assertTrue(pipeline.stop(1L, 0L).isEmpty())
    }

    @Test fun noDataContextIsExplicit() {
        val context = PreprocessingContextEvaluator().evaluate(AnalysisWindow(1L, 1L, 0L, 1L, true, emptyList(), emptyList(), 0L), SignalFeatures())

        assertEquals(DataQuality.NoData, context.quality)
    }

    @Test fun insufficientContextDoesNotInventFeatures() {
        val invalid = FilteredSignalSample(vectorEvent(SignalSourceId.Phone, 1L), null, valid = false)
        val context = PreprocessingContextEvaluator().evaluate(AnalysisWindow(1L, 1L, 0L, 1L, true, listOf(invalid), emptyList(), 0L), SignalFeatures())

        assertEquals(DataQuality.Insufficient, context.quality)
    }

    @Test fun gpsFeaturesUseAccuracyWithoutNormalizingCoordinates() {
        val location = LocationSample(19.0, -99.0, 7f, timestampMillis = 1L, provider = "gps", isMock = false)
        val sample = FilteredSignalSample(
            RawSignalEvent(SignalSourceId.Phone, SignalKind.Location, SignalAvailability.Available, RawSignalValue.Location(location), ProcessingTimestamp(10L, 1_000_000L, TimeDomain.PhoneWallClock)),
            RawSignalValue.Location(location),
            true
        )

        val features = SignalFeatureExtractor().extract(AnalysisWindow(1L, 1L, 0L, 20L, false, listOf(sample), emptyList(), 0L), listOf(WindowedSample(sample)))

        assertEquals(1, features.gps!!.validLocationCount)
        assertEquals(7.0, features.gps!!.meanAccuracyMeters!!, 0.0001)
    }

    @Test fun latestBatteryConnectivityAndWearStatusAreCarriedAsDiscreteContext() {
        val battery = BatterySample(77, false, 10L)
        val connectivity = ConnectivitySample(true, true, false, NetworkTransport.Wifi, 11L)
        val samples = listOf(
            discrete(SignalKind.Battery, RawSignalValue.Battery(battery), 1L),
            discrete(SignalKind.Connectivity, RawSignalValue.Connectivity(connectivity), 2L),
            discrete(SignalKind.WearableStatus, RawSignalValue.WearStatus(WearableStatus.Capturing), 3L, SignalSourceId.Wear)
        )

        val features = SignalFeatureExtractor().extract(AnalysisWindow(1L, 1L, 0L, 10L, false, samples, emptyList(), 0L), samples.map { WindowedSample(it) })

        assertEquals(77, features.latestBattery!!.percentage)
        assertEquals(NetworkTransport.Wifi, features.latestConnectivity!!.transport)
        assertEquals(WearableStatus.Capturing, features.latestWearableStatus)
    }

    @Test fun heartRateFeaturesExistOnlyForValidHeartRateData() {
        val sample = FilteredSignalSample(
            RawSignalEvent(SignalSourceId.Wear, SignalKind.HeartRate, SignalAvailability.Available, RawSignalValue.Scalar(72.0), ProcessingTimestamp(1L, null, TimeDomain.WearElapsedRealtime, 1L)),
            RawSignalValue.Scalar(72.0),
            true
        )

        val features = SignalFeatureExtractor(PreprocessingConfig(minimumSamplesPerWindow = 1)).extract(AnalysisWindow(1L, 1L, 0L, 10L, false, listOf(sample), emptyList(), 0L), listOf(WindowedSample(sample)))

        assertNotNull(features.scalar[SignalKind.HeartRate])
        assertEquals(72.0, features.scalar[SignalKind.HeartRate]!!.mean!!, 0.0001)
    }

    @Test fun vectorRmsUsesMagnitudeNotAxisPlaceholder() {
        val sample = WindowedSample(filteredVector(SignalSourceId.Phone, 1L, 3.0, 4.0, 0.0))
        val features = SignalFeatureExtractor(PreprocessingConfig(minimumSamplesPerWindow = 1)).extract(AnalysisWindow(1L, 1L, 0L, 10L, false, listOf(sample.sample), emptyList(), 0L), listOf(sample))

        assertEquals(5.0, features.vector[SignalKind.Accelerometer]!!.magnitudeRootMeanSquare!!, 0.0001)
    }

    @Test fun configRejectsInvalidMagicNumbers() {
        var rejected = false
        try {
            PreprocessingConfig(windowDurationNanos = 0L)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue(rejected)
    }

    @Test fun normalizationMetadataDistinguishesConstantSignalFromAbsence() {
        val result = SignalNormalizer().normalize(listOf(2.0, 2.0, 2.0)) as NormalizationResult.Values

        assertEquals(0.0, result.standardDeviation, 0.0001)
        assertEquals(3, result.normalized.size)
    }

    @Test fun nonFiniteOutlierInputsAreNotFlaggedAsZero() {
        val flags = OutlierDetector().detect(listOf(1.0, Double.NaN, 1.0))

        assertFalse(flags[1].isOutlier)
        assertEquals("insufficient_data", flags[1].reason)
    }

    @Test fun averageSynchronizationDeltaUsesPhoneTimeline() {
        val phone = filteredVector(SignalSourceId.Phone, 100L)
        val wear = filteredVector(SignalSourceId.Wear, 110L)
        val frame = SynchronizedSignalFrame(phone, wear, 10L, true, emptySet())
        val context = PreprocessingContextEvaluator().evaluate(AnalysisWindow(1L, 1L, 0L, 200L, false, listOf(phone, wear), listOf(frame), 0L), SignalFeatures())

        assertEquals(10.0, context.averageSynchronizationDeltaNanos!!, 0.0001)
    }

    @Test fun wearableUnavailableStatusStillMarksWearSignalPresentWithoutSamples() {
        val sample = discrete(SignalKind.WearableStatus, RawSignalValue.WearStatus(WearableStatus.PermissionRequired), 1L, SignalSourceId.Wear)
        val context = PreprocessingContextEvaluator().evaluate(AnalysisWindow(1L, 1L, 0L, 10L, true, listOf(sample), emptyList(), 0L), SignalFeatures(latestWearableStatus = WearableStatus.PermissionRequired))

        assertTrue(context.wearableAvailable)
        assertFalse(context.heartRateAvailable)
    }

    @Test fun rangeAndCoverageAreReportedForScalarFeatures() {
        val samples = listOf(1.0, 3.0, 5.0).mapIndexed { index, value -> WindowedSample(scalarSample(index.toLong(), value)) }
        val features = SignalFeatureExtractor(PreprocessingConfig(minimumSamplesPerWindow = 3)).extract(AnalysisWindow(1L, 1L, 0L, 10L, false, samples.map { it.sample }, emptyList(), 0L), samples)

        assertEquals(4.0, features.scalar[SignalKind.Speed]!!.range!!, 0.0001)
        assertEquals(1.0, features.scalar[SignalKind.Speed]!!.coverageRatio, 0.0001)
    }

    private fun vectorEvent(source: SignalSourceId, time: Long) = RawSignalEvent(source, SignalKind.Accelerometer, SignalAvailability.Available, RawSignalValue.Vector(1.0, 0.0, 0.0), ProcessingTimestamp(time, time, if (source == SignalSourceId.Wear) TimeDomain.WearElapsedRealtime else TimeDomain.PhoneElapsedRealtime, if (source == SignalSourceId.Wear) time else null))

    private fun filteredVector(source: SignalSourceId, time: Long, x: Double = 1.0, y: Double = 0.0, z: Double = 0.0): FilteredSignalSample =
        FilteredSignalSample(vectorEvent(source, time).copy(value = RawSignalValue.Vector(x, y, z)), RawSignalValue.Vector(x, y, z), true)

    private fun scalarSample(time: Long, value: Double): FilteredSignalSample = FilteredSignalSample(
        RawSignalEvent(SignalSourceId.Phone, SignalKind.Speed, SignalAvailability.Available, RawSignalValue.Scalar(value), ProcessingTimestamp(time, time, TimeDomain.PhoneWallClock)),
        RawSignalValue.Scalar(value),
        true
    )

    private fun discrete(kind: SignalKind, value: RawSignalValue, time: Long, source: SignalSourceId = SignalSourceId.Phone): FilteredSignalSample = FilteredSignalSample(
        RawSignalEvent(source, kind, SignalAvailability.Available, value, ProcessingTimestamp(time)),
        value,
        true
    )
}
