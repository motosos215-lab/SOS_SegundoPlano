package com.example.sos_segundoplano.wearprotocol

/**
 * Modelos del contrato móvil–Wear del protocolo V1.
 *
 * Unidades fijadas por el contrato (no se transmiten como texto en cada respuesta):
 *  - acelerómetro: m/s²
 *  - giroscopio: rad/s
 *  - frecuencia cardiaca: bpm (látidos por minuto)
 *
 * Toda solicitud y toda respuesta incluye: [protocolVersion], [schemaVersion], [requestId]
 * y [timestampEpochMs] en epoch milliseconds.
 */

data class HandshakeRequest(
    val protocolVersion: Int = WearProtocol.PROTOCOL_VERSION,
    val schemaVersion: Int = WearProtocol.SCHEMA_VERSION,
    val requestId: String,
    val timestampEpochMs: Long
)

data class HandshakeResponse(
    val protocolVersion: Int,
    val schemaVersion: Int,
    val requestId: String,
    val result: WearRpcResult,
    val appVersionName: String,
    val appVersionCode: Long,
    val manufacturer: String,
    val model: String,
    val wearOsApiLevel: Int,
    val capability: String,
    val respondedAtEpochMs: Long
)

data class WatchStatusRequest(
    val protocolVersion: Int = WearProtocol.PROTOCOL_VERSION,
    val schemaVersion: Int = WearProtocol.SCHEMA_VERSION,
    val requestId: String,
    val timestampEpochMs: Long
)

data class WatchStatusResponse(
    val protocolVersion: Int,
    val schemaVersion: Int,
    val requestId: String,
    val result: WearRpcResult,
    val sequence: Long,
    val batteryAvailable: Boolean,
    val batteryPercent: Int?,
    val chargingKnown: Boolean,
    val charging: Boolean?,
    val accelerometerAvailable: Boolean,
    val gyroscopeAvailable: Boolean,
    val heartRateAvailable: Boolean,
    val heartRatePermission: SensorPermissionState,
    val respondedAtEpochMs: Long
)

data class WatchSnapshotRequest(
    val protocolVersion: Int = WearProtocol.PROTOCOL_VERSION,
    val schemaVersion: Int = WearProtocol.SCHEMA_VERSION,
    val requestId: String,
    val timestampEpochMs: Long
)

data class WatchSnapshotResponse(
    val protocolVersion: Int,
    val schemaVersion: Int,
    val requestId: String,
    val result: WearRpcResult,
    val sequence: Long,
    val capturedAtEpochMs: Long,
    val accelerometerAvailable: Boolean,
    val accelerometerX: Float?,
    val accelerometerY: Float?,
    val accelerometerZ: Float?,
    val gyroscopeAvailable: Boolean,
    val gyroscopeX: Float?,
    val gyroscopeY: Float?,
    val gyroscopeZ: Float?,
    val heartRateAvailable: Boolean,
    val heartRatePermission: SensorPermissionState,
    val heartRateBpmPresent: Boolean,
    val heartRateBpm: Float?
)

/** Lectura vectorial. Para acelerómetro las unidades son m/s²; para giroscopio rad/s. */
data class VectorReading(val x: Float, val y: Float, val z: Float) {
    val isFinite: Boolean get() = x.isFinite() && y.isFinite() && z.isFinite()
}

/** Frecuencia cardiaca en bpm. No se aplica diagnóstico médico a este valor. */
data class HeartRateReading(val bpm: Float) {
    val isRepresentable: Boolean get() = bpm.isFinite() && bpm >= 0f
}