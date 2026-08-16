package com.example.sos_segundoplano.wear

import android.os.Build
import com.example.sos_segundoplano.wearprotocol.HandshakeResponse
import com.example.sos_segundoplano.wearprotocol.SensorPermissionState
import com.example.sos_segundoplano.wearprotocol.WatchSnapshotResponse
import com.example.sos_segundoplano.wearprotocol.WatchStatusResponse
import com.example.sos_segundoplano.wearprotocol.WearDecodeResult
import com.example.sos_segundoplano.wearprotocol.WearProtocol
import com.example.sos_segundoplano.wearprotocol.WearProtocolCodec
import com.example.sos_segundoplano.wearprotocol.WearRpcResult
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.WearableListenerService

/**
 * Request/response surface for the companion app.  It reads [WearSignalStateStore], which is
 * fed by [WearSignalForegroundService], and deliberately never starts a second sensor pipeline.
 */
class MotoSosWearRpcService : WearableListenerService() {
    override fun onRequest(nodeId: String, path: String, request: ByteArray): Task<ByteArray>? {
        val now = System.currentTimeMillis()
        val response = when (path) {
            WearProtocol.PATH_HANDSHAKE -> when (val decoded = WearProtocolCodec.decodeHandshakeRequest(request)) {
                is WearDecodeResult.Success -> WearProtocolCodec.encodeHandshakeResponse(
                    HandshakeResponse(
                        protocolVersion = WearProtocol.PROTOCOL_VERSION,
                        schemaVersion = WearProtocol.SCHEMA_VERSION,
                        requestId = decoded.value.requestId,
                        result = WearRpcResult.OK,
                        appVersionName = BuildConfig.VERSION_NAME,
                        appVersionCode = BuildConfig.VERSION_CODE.toLong(),
                        manufacturer = Build.MANUFACTURER.orEmpty().ifBlank { "unknown" },
                        model = Build.MODEL.orEmpty().ifBlank { "unknown" },
                        wearOsApiLevel = Build.VERSION.SDK_INT,
                        capability = WearProtocol.CAPABILITY_WEAR_TELEMETRY,
                        respondedAtEpochMs = now
                    )
                )
                is WearDecodeResult.Failure -> return Tasks.forResult(ByteArray(0))
            }
            WearProtocol.PATH_STATUS -> when (val decoded = WearProtocolCodec.decodeWatchStatusRequest(request)) {
                is WearDecodeResult.Success -> WearProtocolCodec.encodeWatchStatusResponse(statusResponse(decoded.value.requestId, now))
                is WearDecodeResult.Failure -> return Tasks.forResult(ByteArray(0))
            }
            WearProtocol.PATH_SNAPSHOT -> when (val decoded = WearProtocolCodec.decodeWatchSnapshotRequest(request)) {
                is WearDecodeResult.Success -> WearProtocolCodec.encodeWatchSnapshotResponse(snapshotResponse(decoded.value.requestId, now))
                is WearDecodeResult.Failure -> return Tasks.forResult(ByteArray(0))
            }
            else -> return null
        }
        return Tasks.forResult(response)
    }

    private fun statusResponse(requestId: String, now: Long): WatchStatusResponse {
        val signal = WearSignalStateStore.snapshot.value
        return WatchStatusResponse(
            protocolVersion = WearProtocol.PROTOCOL_VERSION,
            schemaVersion = WearProtocol.SCHEMA_VERSION,
            requestId = requestId,
            result = WearRpcResult.OK,
            sequence = signal.lastUpdatedMillis.coerceAtLeast(0L),
            batteryAvailable = signal.watchBatteryPercentage != null,
            batteryPercent = signal.watchBatteryPercentage,
            chargingKnown = false,
            charging = null,
            accelerometerAvailable = signal.accelerometerStatus == WearSignalAvailability.Available,
            gyroscopeAvailable = signal.gyroscopeStatus == WearSignalAvailability.Available,
            heartRateAvailable = signal.heartRateStatus == WearSignalAvailability.Available,
            heartRatePermission = signal.heartRateStatus.toPermissionState(),
            respondedAtEpochMs = now
        )
    }

    private fun snapshotResponse(requestId: String, now: Long): WatchSnapshotResponse {
        val signal = WearSignalStateStore.snapshot.value
        val accelerometer = signal.accelerometer?.takeIf { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() }
        val gyroscope = signal.gyroscope?.takeIf { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() }
        val heartRate = signal.heartRateBpm?.takeIf { it.isFinite() && it >= 0.0 }?.toFloat()
        return WatchSnapshotResponse(
            protocolVersion = WearProtocol.PROTOCOL_VERSION,
            schemaVersion = WearProtocol.SCHEMA_VERSION,
            requestId = requestId,
            result = WearRpcResult.OK,
            sequence = signal.lastUpdatedMillis.coerceAtLeast(0L),
            capturedAtEpochMs = signal.lastUpdatedMillis.takeIf { it > 0L } ?: now,
            accelerometerAvailable = accelerometer != null,
            accelerometerX = accelerometer?.x,
            accelerometerY = accelerometer?.y,
            accelerometerZ = accelerometer?.z,
            gyroscopeAvailable = gyroscope != null,
            gyroscopeX = gyroscope?.x,
            gyroscopeY = gyroscope?.y,
            gyroscopeZ = gyroscope?.z,
            heartRateAvailable = heartRate != null,
            heartRatePermission = signal.heartRateStatus.toPermissionState(),
            heartRateBpmPresent = heartRate != null,
            heartRateBpm = heartRate
        )
    }

    private fun WearSignalAvailability.toPermissionState(): SensorPermissionState = when (this) {
        WearSignalAvailability.PermissionRequired -> SensorPermissionState.NOT_REQUESTED
        WearSignalAvailability.PermanentlyDenied -> SensorPermissionState.DENIED
        WearSignalAvailability.Unsupported,
        WearSignalAvailability.HealthServicesUnavailable -> SensorPermissionState.UNAVAILABLE
        else -> SensorPermissionState.GRANTED
    }
}
