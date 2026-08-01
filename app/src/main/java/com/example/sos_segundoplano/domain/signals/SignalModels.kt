package com.example.sos_segundoplano.domain.signals

sealed interface SignalAvailability {
    data object Available : SignalAvailability
    data object Waiting : SignalAvailability
    data object PermissionMissing : SignalAvailability
    data object Disabled : SignalAvailability
    data object Unsupported : SignalAvailability
    data object Stale : SignalAvailability
    data class Error(val reason: String? = null) : SignalAvailability
}

sealed interface CaptureState {
    data object Idle : CaptureState
    data object Starting : CaptureState
    data object Active : CaptureState
    data object Stopped : CaptureState
    data class Error(val reason: String? = null) : CaptureState
}

sealed interface WearableStatus {
    data object NotInstalledOrUnavailable : WearableStatus
    data object Disconnected : WearableStatus
    data object ConnectedNearby : WearableStatus
    data object ConnectedRemote : WearableStatus
    data object PermissionMissing : WearableStatus
    data object SensorUnavailable : WearableStatus
    data object Capturing : WearableStatus
    data object Stale : WearableStatus
    data class Error(val reason: String? = null) : WearableStatus
    data object UserActionRequired : WearableStatus
}

data class SignalReading<out T>(
    val availability: SignalAvailability,
    val sample: T? = null
)

data class Vector3Sample(
    val x: Float,
    val y: Float,
    val z: Float,
    val timestampNanos: Long,
    val accuracy: Int? = null
)

data class LocationSample(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val altitudeMeters: Double? = null,
    val bearingDegrees: Float? = null,
    val speedMetersPerSecond: Float? = null,
    val timestampMillis: Long,
    val provider: String,
    val isMock: Boolean
)

data class SpeedSample(
    val metersPerSecond: Float,
    val timestampMillis: Long,
    val source: SpeedSource
)

enum class SpeedSource {
    DirectLocation,
    DerivedLocation
}

data class BatterySample(
    val percentage: Int,
    val isCharging: Boolean,
    val timestampMillis: Long
)

data class ConnectivitySample(
    val connected: Boolean,
    val validated: Boolean,
    val metered: Boolean,
    val transport: NetworkTransport,
    val timestampMillis: Long
)

enum class NetworkTransport {
    Wifi,
    Cellular,
    Ethernet,
    Vpn,
    Bluetooth,
    Other,
    None
}

data class WearableSample(
    val accelerometer: SignalReading<Vector3Sample>? = null,
    val gyroscope: SignalReading<Vector3Sample>? = null,
    val heartRateBpm: Double? = null,
    val watchBatteryPercentage: Int? = null,
    val nodeId: String? = null,
    val isNearby: Boolean = false,
    val captureActive: Boolean = false,
    val lastUpdatedMillis: Long = 0L,
    val status: WearableStatus = WearableStatus.Disconnected
)

data class TripSignalSnapshot(
    val location: SignalReading<LocationSample> = SignalReading(SignalAvailability.Waiting),
    val mobileAccelerometer: SignalReading<Vector3Sample> = SignalReading(SignalAvailability.Waiting),
    val mobileGyroscope: SignalReading<Vector3Sample> = SignalReading(SignalAvailability.Waiting),
    val speed: SignalReading<SpeedSample> = SignalReading(SignalAvailability.Waiting),
    val phoneBattery: SignalReading<BatterySample> = SignalReading(SignalAvailability.Waiting),
    val connectivity: SignalReading<ConnectivitySample> = SignalReading(SignalAvailability.Waiting),
    val wearable: WearableSample = WearableSample(),
    val captureState: CaptureState = CaptureState.Idle,
    val lastUpdatedMillis: Long = 0L
)
