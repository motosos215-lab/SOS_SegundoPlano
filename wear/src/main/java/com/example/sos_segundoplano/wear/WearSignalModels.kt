package com.example.sos_segundoplano.wear

enum class WearSignalAvailability {
    Available,
    Waiting,
    PermissionRequired,
    PermanentlyDenied,
    Unsupported,
    HealthServicesUnavailable,
    StartFailed,
    Error,
    Stopped
}

enum class WearCaptureStatus {
    Disconnected,
    Capturing,
    PermissionRequired,
    PermanentlyDenied,
    SensorUnavailable,
    HealthServicesUnavailable,
    StartFailed,
    Error,
    UserActionRequired,
    Stopped
}

data class WearVectorSample(
    val x: Float,
    val y: Float,
    val z: Float,
    val timestampNanos: Long,
    val accuracy: Int?
)

data class WearSignalSnapshot(
    val accelerometer: WearVectorSample? = null,
    val accelerometerStatus: WearSignalAvailability = WearSignalAvailability.Waiting,
    val gyroscope: WearVectorSample? = null,
    val gyroscopeStatus: WearSignalAvailability = WearSignalAvailability.Waiting,
    val heartRateBpm: Double? = null,
    val heartRateStatus: WearSignalAvailability = WearSignalAvailability.Waiting,
    val watchBatteryPercentage: Int? = null,
    val captureActive: Boolean = false,
    val status: WearCaptureStatus = WearCaptureStatus.Disconnected,
    val lastUpdatedMillis: Long = 0L
)
