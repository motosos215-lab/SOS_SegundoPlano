package com.example.sos_segundoplano.domain.preprocessing

import com.example.sos_segundoplano.domain.signals.BatterySample
import com.example.sos_segundoplano.domain.signals.ConnectivitySample
import com.example.sos_segundoplano.domain.signals.LocationSample
import com.example.sos_segundoplano.domain.signals.SignalAvailability
import com.example.sos_segundoplano.domain.signals.Vector3Sample
import com.example.sos_segundoplano.domain.signals.WearableStatus

data class PreprocessingConfig(
    val rawEventBufferCapacity: Int = 256,
    val retentionNanos: Long = 12_000_000_000L,
    val windowDurationNanos: Long = 2_000_000_000L,
    val windowStepNanos: Long = 1_000_000_000L,
    val synchronizationToleranceNanos: Long = 250_000_000L,
    val minimumSamplesPerWindow: Int = 3,
    val lowPassTimeConstantNanos: Long = 250_000_000L,
    val medianFilterSize: Int = 3,
    val outlierThreshold: Double = 3.5,
    val minimumValuesForStatistics: Int = 3,
    val minimumCoverageRatio: Double = 0.5,
    val emitPartialWindowOnStop: Boolean = true
) {
    init {
        require(rawEventBufferCapacity > 0)
        require(retentionNanos >= windowDurationNanos)
        require(windowDurationNanos > 0)
        require(windowStepNanos > 0)
        require(synchronizationToleranceNanos >= 0)
        require(minimumSamplesPerWindow > 0)
        require(lowPassTimeConstantNanos > 0)
        require(medianFilterSize > 0)
        require(outlierThreshold > 0.0)
        require(minimumValuesForStatistics > 0)
        require(minimumCoverageRatio in 0.0..1.0)
    }
}

enum class SignalSourceId { Phone, Wear }
enum class SignalKind { Accelerometer, Gyroscope, Speed, HeartRate, Location, Battery, Connectivity, WearableStatus }
enum class TimeDomain { PhoneElapsedRealtime, PhoneWallClock, WearElapsedRealtime, Unknown }

data class ProcessingTimestamp(
    val phoneTimeNanos: Long,
    val originalTimestampNanos: Long? = null,
    val originalTimeDomain: TimeDomain = TimeDomain.Unknown,
    val receivedAtPhoneNanos: Long? = null
)

sealed interface RawSignalValue {
    data class Vector(val x: Double, val y: Double, val z: Double) : RawSignalValue
    data class Scalar(val value: Double) : RawSignalValue
    data class Location(val sample: LocationSample) : RawSignalValue
    data class Battery(val sample: BatterySample) : RawSignalValue
    data class Connectivity(val sample: ConnectivitySample) : RawSignalValue
    data class WearStatus(val status: WearableStatus) : RawSignalValue
}

data class RawSignalEvent(
    val sourceId: SignalSourceId,
    val signalKind: SignalKind,
    val availability: SignalAvailability,
    val value: RawSignalValue?,
    val timestamp: ProcessingTimestamp,
    val sequenceId: Long = 0L
)

data class FilteredSignalSample(
    val event: RawSignalEvent,
    val filteredValue: RawSignalValue?,
    val valid: Boolean,
    val invalidReason: String? = null
)

data class OutlierFlag(
    val isOutlier: Boolean,
    val score: Double? = null,
    val reason: String? = null
)

data class WindowedSample(
    val sample: FilteredSignalSample,
    val outlier: OutlierFlag = OutlierFlag(false),
    val normalizedValue: RawSignalValue? = null
)

data class SynchronizedSignalFrame(
    val phoneSample: FilteredSignalSample,
    val wearSample: FilteredSignalSample?,
    val deltaNanos: Long?,
    val withinTolerance: Boolean,
    val missingSources: Set<SignalSourceId>
)

data class AnalysisWindow(
    val sessionId: Long,
    val windowId: Long,
    val startNanos: Long,
    val endNanos: Long,
    val partial: Boolean,
    val samples: List<FilteredSignalSample>,
    val synchronizedFrames: List<SynchronizedSignalFrame>,
    val droppedEvents: Long
)

data class ScalarFeatures(
    val count: Int,
    val validCount: Int,
    val outlierCount: Int,
    val mean: Double?,
    val min: Double?,
    val max: Double?,
    val median: Double?,
    val standardDeviation: Double?,
    val rootMeanSquare: Double?,
    val range: Double?,
    val coverageRatio: Double,
    val sufficient: Boolean
)

data class VectorFeatures(
    val x: ScalarFeatures,
    val y: ScalarFeatures,
    val z: ScalarFeatures,
    val magnitudeMean: Double?,
    val magnitudeMax: Double?,
    val magnitudeStandardDeviation: Double?,
    val magnitudeRootMeanSquare: Double?,
    val outlierCount: Int
)

data class GpsFeatures(
    val validLocationCount: Int,
    val meanAccuracyMeters: Double?,
    val maxAccuracyMeters: Double?,
    val lastLocationAgeNanos: Long?
)

data class SignalFeatures(
    val scalar: Map<SignalKind, ScalarFeatures> = emptyMap(),
    val vector: Map<SignalKind, VectorFeatures> = emptyMap(),
    val gps: GpsFeatures? = null,
    val latestBattery: BatterySample? = null,
    val latestConnectivity: ConnectivitySample? = null,
    val latestWearableStatus: WearableStatus? = null
)

enum class DataQuality { Sufficient, Partial, Insufficient, NoData }

data class PreprocessingContext(
    val quality: DataQuality,
    val mobileCoverageRatio: Double,
    val wearCoverageRatio: Double,
    val wearableAvailable: Boolean,
    val heartRateAvailable: Boolean,
    val gpsAvailable: Boolean,
    val connectivityAvailable: Boolean,
    val batteryAvailable: Boolean,
    val droppedEvents: Long,
    val averageSynchronizationDeltaNanos: Double?,
    val presentSignals: Set<SignalKind>,
    val absentSignals: Set<SignalKind>,
    val partialWindow: Boolean
)

data class ProcessedSignalWindow(
    val sessionId: Long,
    val windowId: Long,
    val startNanos: Long,
    val endNanos: Long,
    val samples: List<WindowedSample>,
    val synchronizedFrames: List<SynchronizedSignalFrame>,
    val features: SignalFeatures,
    val context: PreprocessingContext
)

sealed interface ProcessedSignalState {
    data object Idle : ProcessedSignalState
    data class Collecting(val sessionId: Long) : ProcessedSignalState
    data class WindowReady(val window: ProcessedSignalWindow) : ProcessedSignalState
    data class InsufficientData(val sessionId: Long, val observedSamples: Int) : ProcessedSignalState
    data object Stopped : ProcessedSignalState
    data class Error(val reason: String? = null) : ProcessedSignalState
}

fun Vector3Sample.toRawVector(): RawSignalValue.Vector = RawSignalValue.Vector(x.toDouble(), y.toDouble(), z.toDouble())
