package com.example.sos_segundoplano.domain.preprocessing

import com.example.sos_segundoplano.domain.signals.SignalAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreprocessingAlgorithmsTest {
    @Test fun accelerometerFilterReducesIsolatedPerturbation() {
        val filter = NoiseFilter(PreprocessingConfig(lowPassTimeConstantNanos = 1_000_000_000L))
        filter.filter(vector(SignalKind.Accelerometer, 0L, 0.0, 0.0, 0.0))
        val spike = filter.filter(vector(SignalKind.Accelerometer, 50_000_000L, 100.0, 0.0, 0.0))

        assertTrue(((spike.filteredValue as RawSignalValue.Vector).x) < 10.0)
    }

    @Test fun gyroscopeFilterUsesIrregularDeltaTime() {
        val filter = NoiseFilter(PreprocessingConfig(lowPassTimeConstantNanos = 100_000_000L))
        filter.filter(vector(SignalKind.Gyroscope, 0L, 0.0, 0.0, 0.0))
        val shortDelta = filter.filter(vector(SignalKind.Gyroscope, 10_000_000L, 10.0, 0.0, 0.0))
        val longDelta = filter.filter(vector(SignalKind.Gyroscope, 1_010_000_000L, 10.0, 0.0, 0.0))

        assertTrue((longDelta.filteredValue as RawSignalValue.Vector).x > (shortDelta.filteredValue as RawSignalValue.Vector).x)
    }

    @Test fun resetClearsPreviousFilterSession() {
        val filter = NoiseFilter()
        filter.filter(vector(SignalKind.Accelerometer, 0L, 9.0, 0.0, 0.0))
        filter.reset()
        val sample = filter.filter(vector(SignalKind.Accelerometer, 10L, 1.0, 0.0, 0.0))

        assertEquals(1.0, (sample.filteredValue as RawSignalValue.Vector).x, 0.0001)
    }

    @Test fun nonFiniteValuesDoNotProduceZeroSamples() {
        val filter = NoiseFilter()
        val nan = filter.filter(vector(SignalKind.Accelerometer, 0L, Double.NaN, 1.0, 1.0))
        val infinite = filter.filter(scalar(SignalKind.Speed, 1L, Double.POSITIVE_INFINITY))

        assertFalse(nan.valid)
        assertNull(nan.filteredValue)
        assertFalse(infinite.valid)
        assertNull(infinite.filteredValue)
    }

    @Test fun movingMedianRequiresConfiguredSampleCount() {
        val filter = NoiseFilter(PreprocessingConfig(medianFilterSize = 3))

        val first = filter.filter(scalar(SignalKind.Speed, 0L, 1.0))
        filter.filter(scalar(SignalKind.Speed, 1L, 100.0))
        val third = filter.filter(scalar(SignalKind.Speed, 2L, 3.0))

        assertEquals(1.0, (first.filteredValue as RawSignalValue.Scalar).value, 0.0001)
        assertEquals(3.0, (third.filteredValue as RawSignalValue.Scalar).value, 0.0001)
    }

    @Test fun zScoreNormalizesMeanAndDeviation() {
        val normalized = SignalNormalizer().normalize(listOf(1.0, 2.0, 3.0)) as NormalizationResult.Values

        assertEquals(0.0, normalized.normalized.average(), 0.0001)
        assertEquals(1.0, normalized.normalized.standardDeviationOrNull()!!, 0.0001)
    }

    @Test fun constantSignalNormalizesToValidZeroValues() {
        val normalized = SignalNormalizer().normalize(listOf(5.0, 5.0, 5.0)) as NormalizationResult.Values

        assertEquals(listOf(0.0, 0.0, 0.0), normalized.normalized)
        assertEquals(0.0, normalized.standardDeviation, 0.0001)
    }

    @Test fun insufficientNormalizationIsExplicit() {
        assertEquals(NormalizationResult.InsufficientData, SignalNormalizer(PreprocessingConfig(minimumValuesForStatistics = 3)).normalize(listOf(1.0, 2.0)))
    }

    @Test fun latitudeAndLongitudeAreNotNormalizedByPipelineFeatureValue() {
        val event = RawSignalEvent(SignalSourceId.Phone, SignalKind.Location, SignalAvailability.Available, null, ProcessingTimestamp(1L))
        val sample = FilteredSignalSample(event, null, valid = false)

        assertFalse(sample.featureValue().isFinite())
    }

    @Test fun phoneAndWearTimeDomainsArePreserved() {
        val phone = vector(SignalKind.Accelerometer, 50L, 1.0, 0.0, 0.0)
        val wear = vector(SignalKind.Accelerometer, 60L, 1.0, 0.0, 0.0, SignalSourceId.Wear, original = 999L, domain = TimeDomain.WearElapsedRealtime)

        assertEquals(TimeDomain.PhoneElapsedRealtime, phone.timestamp.originalTimeDomain)
        assertEquals(50L, phone.timestamp.phoneTimeNanos)
        assertEquals(TimeDomain.WearElapsedRealtime, wear.timestamp.originalTimeDomain)
        assertEquals(60L, wear.timestamp.phoneTimeNanos)
        assertEquals(999L, wear.timestamp.originalTimestampNanos)
    }

    @Test fun synchronizationPairsOnlyWithinToleranceAndDoesNotReuseWear() {
        val synchronizer = SignalSynchronizer(PreprocessingConfig(synchronizationToleranceNanos = 10L))
        val samples = listOf(
            FilteredSignalSample(vector(SignalKind.Accelerometer, 100L, 1.0, 0.0, 0.0), RawSignalValue.Vector(1.0, 0.0, 0.0), true),
            FilteredSignalSample(vector(SignalKind.Accelerometer, 105L, 1.0, 0.0, 0.0, SignalSourceId.Wear), RawSignalValue.Vector(1.0, 0.0, 0.0), true),
            FilteredSignalSample(vector(SignalKind.Accelerometer, 200L, 1.0, 0.0, 0.0), RawSignalValue.Vector(1.0, 0.0, 0.0), true)
        )

        val frames = synchronizer.synchronize(samples)

        assertTrue(frames.first().withinTolerance)
        assertFalse(frames.last().withinTolerance)
        assertNull(frames.last().wearSample)
    }

    @Test fun absentWearAndPermissionRequiredDoNotCreateZeroSamples() {
        val synchronizer = SignalSynchronizer()
        val phone = FilteredSignalSample(vector(SignalKind.Gyroscope, 100L, 1.0, 1.0, 1.0), RawSignalValue.Vector(1.0, 1.0, 1.0), true)

        val frame = synchronizer.synchronize(listOf(phone)).single()

        assertNull(frame.wearSample)
        assertTrue(frame.missingSources.contains(SignalSourceId.Wear))
    }

    @Test fun duplicateAndOutOfOrderEventsAreHandledDeterministically() {
        val pipeline = PreprocessingPipeline(PreprocessingConfig(windowDurationNanos = 100L, windowStepNanos = 100L, minimumSamplesPerWindow = 1))
        val outputs = listOf(
            vector(SignalKind.Accelerometer, 90L, 1.0, 0.0, 0.0),
            vector(SignalKind.Accelerometer, 10L, 1.0, 0.0, 0.0),
            vector(SignalKind.Accelerometer, 10L, 1.0, 0.0, 0.0),
            vector(SignalKind.Accelerometer, 150L, 1.0, 0.0, 0.0)
        ).flatMap { pipeline.accept(1L, it, 0L) }

        assertEquals(listOf(0L), outputs.map { it.windowId }.distinct())
    }

    @Test fun madDetectsIsolatedPeakButNotConstantSignal() {
        val detector = OutlierDetector(PreprocessingConfig(minimumValuesForStatistics = 3, outlierThreshold = 3.0))
        val peak = detector.detect(listOf(1.0, 1.0, 1.0, 20.0, 1.0))
        val constant = detector.detect(listOf(5.0, 5.0, 5.0, 5.0))

        assertTrue(peak.any { it.isOutlier })
        assertTrue(constant.none { it.isOutlier })
    }

    @Test fun outlierFlagKeepsOriginalSample() {
        val event = scalar(SignalKind.Speed, 1L, 99.0)
        val sample = WindowedSample(FilteredSignalSample(event, event.value, true), OutlierFlag(true, 10.0))

        assertEquals(99.0, (sample.sample.filteredValue as RawSignalValue.Scalar).value, 0.0001)
        assertTrue(sample.outlier.isOutlier)
    }

    @Test fun windowsUseTimeDurationOverlapAndNoDuplicateWindowIds() {
        val generator = AnalysisWindowGenerator(PreprocessingConfig(windowDurationNanos = 100L, windowStepNanos = 50L, minimumSamplesPerWindow = 1))
        val samples = listOf(0L, 50L, 100L, 150L, 200L).map {
            FilteredSignalSample(vector(SignalKind.Accelerometer, it, 1.0, 0.0, 0.0), RawSignalValue.Vector(1.0, 0.0, 0.0), true)
        }

        val windows = generator.windows(1L, samples, forcePartial = false, droppedEvents = 0L)

        assertEquals(listOf(0L, 1L, 2L), windows.map { it.windowId })
        assertEquals(100L, windows.first().endNanos - windows.first().startNanos)
    }

    @Test fun windowPartialIdentifiesMissingSignalsAndRetentionDiscardsOldData() {
        val pipeline = PreprocessingPipeline(PreprocessingConfig(retentionNanos = 100L, windowDurationNanos = 100L, windowStepNanos = 100L, minimumSamplesPerWindow = 1))
        pipeline.accept(1L, vector(SignalKind.Accelerometer, 0L, 1.0, 0.0, 0.0), 0L)
        pipeline.accept(1L, vector(SignalKind.Accelerometer, 500L, 1.0, 0.0, 0.0), 0L)
        val final = pipeline.stop(1L, 0L).last()

        assertTrue(final.context.partialWindow)
        assertTrue(final.context.absentSignals.contains(SignalKind.HeartRate))
        assertTrue(final.samples.none { it.sample.event.timestamp.phoneTimeNanos == 0L })
    }

    @Test fun featureExtractionCalculatesScalarAndVectorStatistics() {
        val window = AnalysisWindow(1L, 1L, 0L, 100L, false, emptyList(), emptyList(), 0L)
        val extractor = SignalFeatureExtractor(PreprocessingConfig(minimumSamplesPerWindow = 1))
        val samples = listOf(
            windowedScalar(SignalKind.Speed, 0L, 1.0),
            windowedScalar(SignalKind.Speed, 1L, 3.0, outlier = true),
            windowedVector(SignalKind.Accelerometer, 2L, 3.0, 4.0, 0.0)
        )

        val features = extractor.extract(window.copy(samples = samples.map { it.sample }), samples)

        assertEquals(2.0, features.scalar[SignalKind.Speed]!!.mean!!, 0.0001)
        assertEquals(1.0, features.scalar[SignalKind.Speed]!!.min!!, 0.0001)
        assertEquals(3.0, features.scalar[SignalKind.Speed]!!.max!!, 0.0001)
        assertEquals(2.0, features.scalar[SignalKind.Speed]!!.median!!, 0.0001)
        assertEquals(1, features.scalar[SignalKind.Speed]!!.outlierCount)
        assertEquals(5.0, features.vector[SignalKind.Accelerometer]!!.magnitudeRootMeanSquare!!, 0.0001)
    }

    @Test fun heartRateFeaturesAbsentWithoutValidDataAndContextStatesAreDescriptive() {
        val evaluator = PreprocessingContextEvaluator(PreprocessingConfig(minimumSamplesPerWindow = 3, minimumCoverageRatio = 0.75))
        val partial = AnalysisWindow(1L, 1L, 0L, 100L, true, listOf(FilteredSignalSample(vector(SignalKind.Accelerometer, 1L, 1.0, 0.0, 0.0), RawSignalValue.Vector(1.0, 0.0, 0.0), true)), emptyList(), 2L)
        val context = evaluator.evaluate(partial, SignalFeatures())

        assertEquals(DataQuality.Partial, context.quality)
        assertFalse(context.heartRateAvailable)
        assertTrue(context.presentSignals.contains(SignalKind.Accelerometer))
        assertEquals(2L, context.droppedEvents)
    }

    @Test fun sufficientContextRequiresConfiguredCoverage() {
        val evaluator = PreprocessingContextEvaluator(PreprocessingConfig(minimumSamplesPerWindow = 2, minimumCoverageRatio = 0.5))
        val samples = listOf(
            FilteredSignalSample(vector(SignalKind.Accelerometer, 1L, 1.0, 0.0, 0.0), RawSignalValue.Vector(1.0, 0.0, 0.0), true),
            FilteredSignalSample(vector(SignalKind.Gyroscope, 2L, 1.0, 0.0, 0.0), RawSignalValue.Vector(1.0, 0.0, 0.0), true)
        )
        val context = evaluator.evaluate(AnalysisWindow(1L, 1L, 0L, 100L, false, samples, emptyList(), 0L), SignalFeatures())

        assertEquals(DataQuality.Sufficient, context.quality)
    }

    private fun vector(kind: SignalKind, time: Long, x: Double, y: Double, z: Double, source: SignalSourceId = SignalSourceId.Phone, original: Long = time, domain: TimeDomain = TimeDomain.PhoneElapsedRealtime) = RawSignalEvent(
        source, kind, SignalAvailability.Available, RawSignalValue.Vector(x, y, z), ProcessingTimestamp(time, original, domain, if (source == SignalSourceId.Wear) time else null)
    )

    private fun scalar(kind: SignalKind, time: Long, value: Double) = RawSignalEvent(
        SignalSourceId.Phone, kind, SignalAvailability.Available, RawSignalValue.Scalar(value), ProcessingTimestamp(time, time, TimeDomain.PhoneWallClock)
    )

    private fun windowedScalar(kind: SignalKind, time: Long, value: Double, outlier: Boolean = false) = WindowedSample(
        FilteredSignalSample(scalar(kind, time, value), RawSignalValue.Scalar(value), true), OutlierFlag(outlier)
    )

    private fun windowedVector(kind: SignalKind, time: Long, x: Double, y: Double, z: Double) = WindowedSample(
        FilteredSignalSample(vector(kind, time, x, y, z), RawSignalValue.Vector(x, y, z), true)
    )
}
