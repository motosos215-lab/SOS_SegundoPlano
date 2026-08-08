package com.example.sos_segundoplano.domain.wear

/**
 * Modelos de dominio de la conexión con el reloj Wear OS de MotoSOS.
 *
 * El `nodeId` del Wearable Data Layer no forma parte del dominio: se conserva únicamente
 * en la capa data para direccionar mensajes y nunca se expone a UI.
 */
data class WatchDeviceSummary(
    val displayName: String,
    val isNearby: Boolean
)

/** Información del reloj confirmada por el handshake del protocolo V1. */
data class WatchHandshakeInfo(
    val appVersionName: String,
    val appVersionCode: Long,
    val manufacturer: String,
    val model: String,
    val wearOsApiLevel: Int
)

/** Estado del reloj reportado en la ruta `/motosos/v1/status`. */
data class WatchStatus(
    val batteryAvailable: Boolean,
    val batteryPercent: Int?,
    val chargingKnown: Boolean,
    val charging: Boolean?,
    val accelerometerAvailable: Boolean,
    val gyroscopeAvailable: Boolean,
    val heartRateAvailable: Boolean,
    val heartRatePermission: HeartRatePermissionState,
    val respondedAtEpochMs: Long
)

/** Lectura de sensores reportada en la ruta `/motosos/v1/snapshot`. */
data class WatchSensorSnapshot(
    val capturedAtEpochMs: Long,
    val accelerometerAvailable: Boolean,
    val accelerometer: VectorReading?,
    val gyroscopeAvailable: Boolean,
    val gyroscope: VectorReading?,
    val heartRateAvailable: Boolean,
    val heartRatePermission: HeartRatePermissionState,
    val heartRateBpm: Float?
)

/** Lectura vectorial. Acelerómetro en m/s²; giroscopio en rad/s. */
data class VectorReading(val x: Float, val y: Float, val z: Float)

/** Estado del permiso de la frecuencia cardiaca declarado por el reloj. */
enum class HeartRatePermissionState {
    Granted,
    Denied,
    NotRequested,
    NotRequired,
    Unavailable
}
