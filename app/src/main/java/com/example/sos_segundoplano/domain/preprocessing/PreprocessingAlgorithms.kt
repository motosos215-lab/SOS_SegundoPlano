package com.example.sos_segundoplano.domain.preprocessing

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

class NoiseFilter(private val config: PreprocessingConfig = PreprocessingConfig()) {
    private val vectorStates = mutableMapOf<Pair<SignalSourceId, SignalKind>, VectorState>()
    private val scalarWindows = mutableMapOf<SignalKind, ArrayDeque<Double>>()

    fun reset() {
        vectorStates.clear()
        scalarWindows.clear()
    }

    fun filter(event: RawSignalEvent): FilteredSignalSample {
        val value = event.value
        if (event.availability != com.example.sos_segundoplano.domain.signals.SignalAvailability.Available) {
            return FilteredSignalSample(event, null, valid = false, invalidReason = "unavailable")
        }
        return when (value) {
            is RawSignalValue.Vector -> filterVector(event, value)
            is RawSignalValue.Scalar -> filterScalar(event, value)
            null -> FilteredSignalSample(event, null, valid = false, invalidReason = "missing")
            else -> FilteredSignalSample(event, value, valid = true)
        }
    }

    private fun filterVector(event: RawSignalEvent, value: RawSignalValue.Vector): FilteredSignalSample {
        if (!value.x.isFinite() || !value.y.isFinite() || !value.z.isFinite()) {
            return FilteredSignalSample(event, null, valid = false, invalidReason = "non_finite")
        }
        val key = event.sourceId to event.signalKind
        val previous = vectorStates[key]
        if (previous == null) {
            vectorStates[key] = VectorState(value, event.timestamp.phoneTimeNanos)
            return FilteredSignalSample(event, value, valid = true)
        }
        val delta = (event.timestamp.phoneTimeNanos - previous.timeNanos).coerceAtLeast(0L).toDouble()
        val alpha = if (delta <= 0.0) 0.0 else delta / (config.lowPassTimeConstantNanos + delta)
        val filtered = RawSignalValue.Vector(
            previous.value.x + alpha * (value.x - previous.value.x),
            previous.value.y + alpha * (value.y - previous.value.y),
            previous.value.z + alpha * (value.z - previous.value.z)
        )
        vectorStates[key] = VectorState(filtered, event.timestamp.phoneTimeNanos)
        return FilteredSignalSample(event, filtered, valid = true)
    }

    private fun filterScalar(event: RawSignalEvent, value: RawSignalValue.Scalar): FilteredSignalSample {
        if (!value.value.isFinite()) return FilteredSignalSample(event, null, valid = false, invalidReason = "non_finite")
        if (event.signalKind != SignalKind.Speed && event.signalKind != SignalKind.HeartRate) {
            return FilteredSignalSample(event, value, valid = true)
        }
        val window = scalarWindows.getOrPut(event.signalKind) { ArrayDeque() }
        window.addLast(value.value)
        while (window.size > config.medianFilterSize) window.removeFirst()
        if (window.size < config.medianFilterSize) return FilteredSignalSample(event, value, valid = true)
        return FilteredSignalSample(event, RawSignalValue.Scalar(window.toList().median()!!), valid = true)
    }

    private data class VectorState(val value: RawSignalValue.Vector, val timeNanos: Long)
}

class SignalNormalizer(private val config: PreprocessingConfig = PreprocessingConfig()) {
    fun normalize(values: List<Double>): NormalizationResult {
        val valid = values.filter { it.isFinite() }
        if (valid.size < config.minimumValuesForStatistics) return NormalizationResult.InsufficientData
        val mean = valid.average()
        val standardDeviation = sqrt(valid.sumOf { (it - mean).pow(2) } / valid.size)
        val normalized = if (standardDeviation == 0.0) {
            valid.map { 0.0 }
        } else {
            valid.map { (it - mean) / standardDeviation }
        }
        return NormalizationResult.Values(normalized, mean, standardDeviation)
    }
}

sealed interface NormalizationResult {
    data object InsufficientData : NormalizationResult
    data class Values(val normalized: List<Double>, val mean: Double, val standardDeviation: Double) : NormalizationResult
}

class SignalSynchronizer(private val config: PreprocessingConfig = PreprocessingConfig()) {
    fun synchronize(samples: List<FilteredSignalSample>): List<SynchronizedSignalFrame> {
        val phone = samples.filter { it.valid && it.event.sourceId == SignalSourceId.Phone && it.event.signalKind in motionKinds }
            .sortedBy { it.event.timestamp.phoneTimeNanos }
        val wearUnused = samples.filter { it.valid && it.event.sourceId == SignalSourceId.Wear && it.event.signalKind in motionKinds }
            .sortedBy { it.event.timestamp.phoneTimeNanos }
            .toMutableList()
        return phone.map { phoneSample ->
            val match = wearUnused.minByOrNull { abs(it.event.timestamp.phoneTimeNanos - phoneSample.event.timestamp.phoneTimeNanos) }
            val delta = match?.let { it.event.timestamp.phoneTimeNanos - phoneSample.event.timestamp.phoneTimeNanos }
            if (match != null && delta != null && abs(delta) <= config.synchronizationToleranceNanos) {
                wearUnused.remove(match)
                SynchronizedSignalFrame(phoneSample, match, delta, withinTolerance = true, missingSources = emptySet())
            } else {
                SynchronizedSignalFrame(phoneSample, null, null, withinTolerance = false, missingSources = setOf(SignalSourceId.Wear))
            }
        }
    }

    private companion object { val motionKinds = setOf(SignalKind.Accelerometer, SignalKind.Gyroscope) }
}

class OutlierDetector(private val config: PreprocessingConfig = PreprocessingConfig()) {
    fun detect(values: List<Double>): List<OutlierFlag> {
        val valid = values.filter { it.isFinite() }
        if (valid.size < config.minimumValuesForStatistics) return values.map { OutlierFlag(false, reason = "insufficient_data") }
        val median = valid.median() ?: return values.map { OutlierFlag(false, reason = "insufficient_data") }
        val deviations = valid.map { abs(it - median) }
        val mad = deviations.median() ?: 0.0
        if (mad == 0.0) {
            return values.map { value ->
                OutlierFlag(value.isFinite() && value != median, reason = if (value == median) "constant_signal" else "zero_mad_nonmedian")
            }
        }
        return values.map { value ->
            if (!value.isFinite()) {
                OutlierFlag(false, reason = "non_finite")
            } else {
                val score = 0.6745 * (value - median) / mad
                OutlierFlag(abs(score) > config.outlierThreshold, score)
            }
        }
    }
}

class AnalysisWindowGenerator(private val config: PreprocessingConfig = PreprocessingConfig()) {
    private var nextStartNanos: Long? = null
    private var emittedWindowId = 0L

    fun reset() {
        nextStartNanos = null
        emittedWindowId = 0L
    }

    fun windows(sessionId: Long, samples: List<FilteredSignalSample>, forcePartial: Boolean, droppedEvents: Long): List<AnalysisWindow> {
        val ordered = samples.sortedBy { it.event.timestamp.phoneTimeNanos }
        val firstTime = ordered.firstOrNull()?.event?.timestamp?.phoneTimeNanos ?: return emptyList()
        if (nextStartNanos == null) nextStartNanos = firstTime
        if (emittedWindowId == 0L) nextStartNanos = minOf(nextStartNanos!!, firstTime)
        val latestTime = ordered.last().event.timestamp.phoneTimeNanos
        val result = mutableListOf<AnalysisWindow>()
        var start = nextStartNanos!!
        while (start + config.windowDurationNanos <= latestTime) {
            val end = start + config.windowDurationNanos
            result += buildWindow(sessionId, start, end, partial = false, ordered, droppedEvents)
            start += config.windowStepNanos
        }
        if (forcePartial && config.emitPartialWindowOnStop && start <= latestTime) {
            result += buildWindow(sessionId, start, latestTime, partial = true, ordered, droppedEvents)
            start = latestTime + config.windowStepNanos
        }
        nextStartNanos = start
        return result
    }

    private fun buildWindow(
        sessionId: Long,
        start: Long,
        end: Long,
        partial: Boolean,
        samples: List<FilteredSignalSample>,
        droppedEvents: Long
    ): AnalysisWindow {
        val windowSamples = samples.filter { it.event.timestamp.phoneTimeNanos in start..end }
        return AnalysisWindow(sessionId, emittedWindowId++, start, end, partial, windowSamples, SignalSynchronizer(config).synchronize(windowSamples), droppedEvents)
    }
}

class SignalFeatureExtractor(private val config: PreprocessingConfig = PreprocessingConfig()) {
    fun extract(window: AnalysisWindow, windowed: List<WindowedSample>): SignalFeatures {
        val scalar = mutableMapOf<SignalKind, ScalarFeatures>()
        val vector = mutableMapOf<SignalKind, VectorFeatures>()
        for (kind in listOf(SignalKind.Speed, SignalKind.HeartRate)) {
            val samples = windowed.filter { it.sample.event.signalKind == kind && it.sample.filteredValue is RawSignalValue.Scalar }
            if (samples.isNotEmpty()) scalar[kind] = scalarFeatures(samples, window)
        }
        for (kind in listOf(SignalKind.Accelerometer, SignalKind.Gyroscope)) {
            val samples = windowed.filter { it.sample.event.signalKind == kind && it.sample.filteredValue is RawSignalValue.Vector }
            if (samples.isNotEmpty()) vector[kind] = vectorFeatures(samples, window)
        }
        val locations = window.samples.mapNotNull { (it.event.value as? RawSignalValue.Location)?.sample }
        val lastTime = window.samples.maxOfOrNull { it.event.timestamp.phoneTimeNanos } ?: window.endNanos
        val gps = if (locations.isEmpty()) null else GpsFeatures(
            validLocationCount = locations.size,
            meanAccuracyMeters = locations.map { it.accuracyMeters.toDouble() }.average(),
            maxAccuracyMeters = locations.maxOf { it.accuracyMeters.toDouble() },
            lastLocationAgeNanos = lastTime - window.samples.filter { it.event.signalKind == SignalKind.Location }.maxOf { it.event.timestamp.phoneTimeNanos }
        )
        return SignalFeatures(
            scalar = scalar,
            vector = vector,
            gps = gps,
            latestBattery = window.samples.mapNotNull { (it.event.value as? RawSignalValue.Battery)?.sample }.maxByOrNull { it.timestampMillis },
            latestConnectivity = window.samples.mapNotNull { (it.event.value as? RawSignalValue.Connectivity)?.sample }.maxByOrNull { it.timestampMillis },
            latestWearableStatus = window.samples.mapNotNull { (it.event.value as? RawSignalValue.WearStatus)?.status }.lastOrNull()
        )
    }

    private fun scalarFeatures(samples: List<WindowedSample>, window: AnalysisWindow): ScalarFeatures {
        val values = samples.mapNotNull { (it.sample.filteredValue as? RawSignalValue.Scalar)?.value }.filter { it.isFinite() }
        return buildScalarFeatures(samples.size, values, samples.count { it.outlier.isOutlier }, window)
    }

    private fun vectorFeatures(samples: List<WindowedSample>, window: AnalysisWindow): VectorFeatures {
        val vectors = samples.mapNotNull { it.sample.filteredValue as? RawSignalValue.Vector }
        val magnitudes = vectors.map { sqrt(it.x * it.x + it.y * it.y + it.z * it.z) }
        return VectorFeatures(
            x = buildScalarFeatures(samples.size, vectors.map { it.x }, samples.count { it.outlier.isOutlier }, window),
            y = buildScalarFeatures(samples.size, vectors.map { it.y }, samples.count { it.outlier.isOutlier }, window),
            z = buildScalarFeatures(samples.size, vectors.map { it.z }, samples.count { it.outlier.isOutlier }, window),
            magnitudeMean = magnitudes.averageOrNull(),
            magnitudeMax = magnitudes.maxOrNull(),
            magnitudeStandardDeviation = magnitudes.standardDeviationOrNull(),
            magnitudeRootMeanSquare = magnitudes.rootMeanSquareOrNull(),
            outlierCount = samples.count { it.outlier.isOutlier }
        )
    }

    private fun buildScalarFeatures(count: Int, values: List<Double>, outliers: Int, window: AnalysisWindow): ScalarFeatures = ScalarFeatures(
        count = count,
        validCount = values.size,
        outlierCount = outliers,
        mean = values.averageOrNull(),
        min = values.minOrNull(),
        max = values.maxOrNull(),
        median = values.median(),
        standardDeviation = values.standardDeviationOrNull(),
        rootMeanSquare = values.rootMeanSquareOrNull(),
        range = if (values.isEmpty()) null else values.maxOrNull()!! - values.minOrNull()!!,
        coverageRatio = coverage(values, window),
        sufficient = values.size >= config.minimumSamplesPerWindow
    )

    private fun coverage(values: List<Double>, window: AnalysisWindow): Double =
        if (values.isEmpty() || window.endNanos <= window.startNanos) 0.0 else 1.0.coerceAtMost(values.size.toDouble() / config.minimumSamplesPerWindow)
}

class PreprocessingContextEvaluator(private val config: PreprocessingConfig = PreprocessingConfig()) {
    fun evaluate(window: AnalysisWindow, features: SignalFeatures): PreprocessingContext {
        val present = window.samples.map { it.event.signalKind }.toSet()
        val absent = SignalKind.values().toSet() - present
        val mobileCount = window.samples.count { it.event.sourceId == SignalSourceId.Phone && it.valid }
        val wearCount = window.samples.count { it.event.sourceId == SignalSourceId.Wear && it.valid }
        val mobileCoverage = 1.0.coerceAtMost(mobileCount.toDouble() / config.minimumSamplesPerWindow)
        val wearCoverage = 1.0.coerceAtMost(wearCount.toDouble() / config.minimumSamplesPerWindow)
        val quality = when {
            window.samples.isEmpty() -> DataQuality.NoData
            mobileCoverage >= config.minimumCoverageRatio && present.contains(SignalKind.Accelerometer) && present.contains(SignalKind.Gyroscope) -> DataQuality.Sufficient
            mobileCoverage > 0.0 -> DataQuality.Partial
            else -> DataQuality.Insufficient
        }
        val deltas = window.synchronizedFrames.mapNotNull { it.deltaNanos?.let(::abs) }
        return PreprocessingContext(
            quality = quality,
            mobileCoverageRatio = mobileCoverage,
            wearCoverageRatio = wearCoverage,
            wearableAvailable = present.contains(SignalKind.WearableStatus) || wearCount > 0,
            heartRateAvailable = features.scalar.containsKey(SignalKind.HeartRate),
            gpsAvailable = features.gps != null,
            connectivityAvailable = features.latestConnectivity != null,
            batteryAvailable = features.latestBattery != null,
            droppedEvents = window.droppedEvents,
            averageSynchronizationDeltaNanos = if (deltas.isEmpty()) null else deltas.average(),
            presentSignals = present,
            absentSignals = absent,
            partialWindow = window.partial
        )
    }
}

class PreprocessingPipeline(private val config: PreprocessingConfig = PreprocessingConfig()) {
    private val filter = NoiseFilter(config)
    private val windows = AnalysisWindowGenerator(config)
    private val normalizer = SignalNormalizer(config)
    private val outliers = OutlierDetector(config)
    private val features = SignalFeatureExtractor(config)
    private val context = PreprocessingContextEvaluator(config)
    private val buffer = ArrayDeque<FilteredSignalSample>()

    fun reset() {
        filter.reset()
        windows.reset()
        buffer.clear()
    }

    fun accept(sessionId: Long, event: RawSignalEvent, droppedEvents: Long): List<ProcessedSignalWindow> {
        val filtered = filter.filter(event)
        buffer.addLast(filtered)
        trim(event.timestamp.phoneTimeNanos)
        return processWindows(sessionId, forcePartial = false, droppedEvents)
    }

    fun stop(sessionId: Long, droppedEvents: Long): List<ProcessedSignalWindow> = processWindows(sessionId, forcePartial = true, droppedEvents).also { reset() }

    private fun trim(nowNanos: Long) {
        while (buffer.isNotEmpty() && nowNanos - buffer.first().event.timestamp.phoneTimeNanos > config.retentionNanos) buffer.removeFirst()
    }

    private fun processWindows(sessionId: Long, forcePartial: Boolean, droppedEvents: Long): List<ProcessedSignalWindow> =
        windows.windows(sessionId, buffer.toList(), forcePartial, droppedEvents).map { window ->
            val windowed = applyOutliersAndNormalization(window)
            val extracted = features.extract(window, windowed)
            ProcessedSignalWindow(window.sessionId, window.windowId, window.startNanos, window.endNanos, windowed, window.synchronizedFrames, extracted, context.evaluate(window, extracted))
        }

    private fun applyOutliersAndNormalization(window: AnalysisWindow): List<WindowedSample> {
        val result = window.samples.map { WindowedSample(it) }.toMutableList()
        val byKind = result.indices.groupBy { result[it].sample.event.signalKind }
        byKind.forEach { (_, indexes) ->
            val values = indexes.map { result[it].sample.featureValue() }
            val flags = outliers.detect(values)
            val normalized = (normalizer.normalize(values.filter { it.isFinite() }) as? NormalizationResult.Values)?.normalized ?: emptyList()
            var normalizedIndex = 0
            indexes.forEachIndexed { localIndex, resultIndex ->
                val original = result[resultIndex]
                val normalizedValue = if (values[localIndex].isFinite() && normalizedIndex < normalized.size) RawSignalValue.Scalar(normalized[normalizedIndex++]) else null
                result[resultIndex] = original.copy(outlier = flags[localIndex], normalizedValue = normalizedValue)
            }
        }
        return result
    }
}

fun FilteredSignalSample.featureValue(): Double = when (val value = filteredValue) {
    is RawSignalValue.Scalar -> value.value
    is RawSignalValue.Vector -> sqrt(value.x * value.x + value.y * value.y + value.z * value.z)
    else -> Double.NaN
}

fun List<Double>.median(): Double? {
    val valid = filter { it.isFinite() }.sorted()
    if (valid.isEmpty()) return null
    val middle = valid.size / 2
    return if (valid.size % 2 == 0) (valid[middle - 1] + valid[middle]) / 2.0 else valid[middle]
}

fun List<Double>.averageOrNull(): Double? = filter { it.isFinite() }.takeIf { it.isNotEmpty() }?.average()
fun List<Double>.standardDeviationOrNull(): Double? = filter { it.isFinite() }.takeIf { it.isNotEmpty() }?.let { values ->
    val mean = values.average()
    sqrt(values.sumOf { (it - mean).pow(2) } / values.size)
}
fun List<Double>.rootMeanSquareOrNull(): Double? = filter { it.isFinite() }.takeIf { it.isNotEmpty() }?.let { values ->
    sqrt(values.sumOf { it * it } / values.size)
}
