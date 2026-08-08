package com.example.sos_segundoplano.data.wear

import com.example.sos_segundoplano.domain.wear.HeartRatePermissionState
import com.example.sos_segundoplano.domain.wear.VectorReading
import com.example.sos_segundoplano.domain.wear.WatchDeclineReason
import com.example.sos_segundoplano.domain.wear.WatchHandshakeInfo
import com.example.sos_segundoplano.domain.wear.WatchSensorSnapshot
import com.example.sos_segundoplano.domain.wear.WatchStatus
import com.example.sos_segundoplano.wearprotocol.HandshakeResponse
import com.example.sos_segundoplano.wearprotocol.SensorPermissionState
import com.example.sos_segundoplano.wearprotocol.WatchSnapshotResponse
import com.example.sos_segundoplano.wearprotocol.WatchStatusResponse
import com.example.sos_segundoplano.wearprotocol.WearRpcResult

/**
 * Convierte las respuestas del contrato compartido (`:wear-protocol`) en modelos de dominio
 * de la app móvil. No realiza ninguna validación: el codec ya descartó payloads inválidos.
 */
object WearProtocolMapper {
    fun toHandshakeInfo(response: HandshakeResponse): WatchHandshakeInfo = WatchHandshakeInfo(
        appVersionName = response.appVersionName,
        appVersionCode = response.appVersionCode,
        manufacturer = response.manufacturer,
        model = response.model,
        wearOsApiLevel = response.wearOsApiLevel
    )

    fun toStatus(response: WatchStatusResponse): WatchStatus = WatchStatus(
        batteryAvailable = response.batteryAvailable,
        batteryPercent = response.batteryPercent,
        chargingKnown = response.chargingKnown,
        charging = response.charging,
        accelerometerAvailable = response.accelerometerAvailable,
        gyroscopeAvailable = response.gyroscopeAvailable,
        heartRateAvailable = response.heartRateAvailable,
        heartRatePermission = toHeartRatePermission(response.heartRatePermission),
        respondedAtEpochMs = response.respondedAtEpochMs
    )

    fun toSnapshot(response: WatchSnapshotResponse): WatchSensorSnapshot {
        val accelerometerX = response.accelerometerX
        val accelerometerY = response.accelerometerY
        val accelerometerZ = response.accelerometerZ
        val accelerometer = if (
            accelerometerX != null &&
            accelerometerY != null &&
            accelerometerZ != null
        ) {
            VectorReading(
                x = accelerometerX,
                y = accelerometerY,
                z = accelerometerZ
            )
        } else {
            null
        }

        val gyroscopeX = response.gyroscopeX
        val gyroscopeY = response.gyroscopeY
        val gyroscopeZ = response.gyroscopeZ
        val gyroscope = if (
            gyroscopeX != null &&
            gyroscopeY != null &&
            gyroscopeZ != null
        ) {
            VectorReading(
                x = gyroscopeX,
                y = gyroscopeY,
                z = gyroscopeZ
            )
        } else {
            null
        }

        return WatchSensorSnapshot(
            capturedAtEpochMs = response.capturedAtEpochMs,
            accelerometerAvailable = response.accelerometerAvailable,
            accelerometer = accelerometer,
            gyroscopeAvailable = response.gyroscopeAvailable,
            gyroscope = gyroscope,
            heartRateAvailable = response.heartRateAvailable,
            heartRatePermission = toHeartRatePermission(response.heartRatePermission),
            heartRateBpm = response.heartRateBpm
        )
    }

    fun toHeartRatePermission(state: SensorPermissionState): HeartRatePermissionState = when (state) {
        SensorPermissionState.GRANTED -> HeartRatePermissionState.Granted
        SensorPermissionState.DENIED -> HeartRatePermissionState.Denied
        SensorPermissionState.NOT_REQUESTED -> HeartRatePermissionState.NotRequested
        SensorPermissionState.NOT_REQUIRED -> HeartRatePermissionState.NotRequired
        SensorPermissionState.UNAVAILABLE -> HeartRatePermissionState.Unavailable
    }

    fun toDeclineReason(result: WearRpcResult): WatchDeclineReason = when (result) {
        WearRpcResult.NOT_READY -> WatchDeclineReason.NotReady
        WearRpcResult.SENSOR_UNAVAILABLE -> WatchDeclineReason.SensorUnavailable
        WearRpcResult.PERMISSION_DENIED -> WatchDeclineReason.PermissionDenied
        WearRpcResult.PROTOCOL_MISMATCH -> WatchDeclineReason.ProtocolMismatch
        WearRpcResult.INVALID_REQUEST -> WatchDeclineReason.InvalidRequest
        WearRpcResult.INTERNAL_ERROR -> WatchDeclineReason.InternalError
        WearRpcResult.OK -> WatchDeclineReason.InvalidRequest
    }
}