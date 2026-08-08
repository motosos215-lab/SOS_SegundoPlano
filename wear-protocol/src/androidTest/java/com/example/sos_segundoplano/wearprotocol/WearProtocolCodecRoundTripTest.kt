package com.example.sos_segundoplano.wearprotocol

import com.google.android.gms.wearable.DataMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas instrumentadas de la serialización a bytes del contrato V1
 * (`DataMap.toByteArray()` / `DataMap.fromByteArray()`), que requiere el runtime de Android.
 */
class WearProtocolCodecRoundTripTest {

    private object Wire {
        const val ROUTE = "route"
        const val PROTOCOL_VERSION = "protocolVersion"
        const val SCHEMA_VERSION = "schemaVersion"
        const val REQUEST_ID = "requestId"
        const val RESULT = "result"

        const val APP_VERSION_NAME = "appVersionName"
        const val APP_VERSION_CODE = "appVersionCode"
        const val MANUFACTURER = "manufacturer"
        const val MODEL = "model"
        const val WEAR_OS_API_LEVEL = "wearOsApiLevel"
        const val CAPABILITY = "capability"
        const val RESPONDED_AT = "respondedAtEpochMs"

        const val SEQUENCE = "sequence"
        const val BATTERY_AVAILABLE = "batteryAvailable"
        const val BATTERY_PERCENT = "batteryPercent"
        const val CHARGING_KNOWN = "chargingKnown"
        const val CHARGING = "charging"
        const val ACCELEROMETER_AVAILABLE = "accelerometerAvailable"
        const val GYROSCOPE_AVAILABLE = "gyroscopeAvailable"
        const val HEART_RATE_AVAILABLE = "heartRateAvailable"
        const val HEART_RATE_PERMISSION = "heartRatePermission"
        const val CAPTURED_AT = "capturedAtEpochMs"
        const val ACCELEROMETER_X = "accelerometerX"
        const val ACCELEROMETER_Y = "accelerometerY"
        const val ACCELEROMETER_Z = "accelerometerZ"
        const val GYROSCOPE_X = "gyroscopeX"
        const val GYROSCOPE_Y = "gyroscopeY"
        const val GYROSCOPE_Z = "gyroscopeZ"
        const val HEART_RATE_BPM_PRESENT = "heartRateBpmPresent"
        const val HEART_RATE_BPM = "heartRateBpm"
        const val BLOB = "unusedLargeBlob"
    }

    @Test
    fun handshakeRoundTripSerializesAndDecodes() {
        val map = envelope(route = WearProtocol.PATH_HANDSHAKE).apply {
            putString(Wire.RESULT, WearRpcResult.OK.wireValue)
            putString(Wire.APP_VERSION_NAME, "MotoSOS Wear")
            putLong(Wire.APP_VERSION_CODE, 3L)
            putString(Wire.MANUFACTURER, "Samsung")
            putString(Wire.MODEL, "Galaxy Watch 6")
            putInt(Wire.WEAR_OS_API_LEVEL, 32)
            putString(Wire.CAPABILITY, WearProtocol.CAPABILITY_WEAR_TELEMETRY)
            putLong(Wire.RESPONDED_AT, 1_700_000_000_110L)
        }

        val parsed = WearProtocolCodec.decodeHandshakeResponse(map.toByteArray()).success()

        assertEquals(WearRpcResult.OK, parsed.result)
        assertEquals("MotoSOS Wear", parsed.appVersionName)
        assertEquals("Samsung", parsed.manufacturer)
        assertEquals("Galaxy Watch 6", parsed.model)
        assertEquals(32, parsed.wearOsApiLevel)
    }

    @Test
    fun statusRoundTripPreservesBatteryAndCharging() {
        val map = envelope(route = WearProtocol.PATH_STATUS).apply {
            putString(Wire.RESULT, WearRpcResult.OK.wireValue)
            putLong(Wire.SEQUENCE, 12L)
            putBoolean(Wire.BATTERY_AVAILABLE, true)
            putInt(Wire.BATTERY_PERCENT, 87)
            putBoolean(Wire.CHARGING_KNOWN, true)
            putBoolean(Wire.CHARGING, false)
            putBoolean(Wire.ACCELEROMETER_AVAILABLE, true)
            putBoolean(Wire.GYROSCOPE_AVAILABLE, false)
            putBoolean(Wire.HEART_RATE_AVAILABLE, true)
            putString(Wire.HEART_RATE_PERMISSION, SensorPermissionState.DENIED.wireValue)
            putLong(Wire.RESPONDED_AT, 1_700_000_000_220L)
        }

        val parsed = WearProtocolCodec.decodeWatchStatusResponse(map.toByteArray()).success()

        assertEquals(12L, parsed.sequence)
        assertEquals(87, parsed.batteryPercent)
        assertEquals(false, parsed.charging)
        assertEquals(false, parsed.gyroscopeAvailable)
        assertEquals(SensorPermissionState.DENIED, parsed.heartRatePermission)
    }

    @Test
    fun snapshotRoundTripPreservesReadings() {
        val map = envelope(route = WearProtocol.PATH_SNAPSHOT).apply {
            putString(Wire.RESULT, WearRpcResult.OK.wireValue)
            putLong(Wire.SEQUENCE, 3L)
            putLong(Wire.CAPTURED_AT, 1_700_000_000_330L)
            putBoolean(Wire.ACCELEROMETER_AVAILABLE, true)
            putFloat(Wire.ACCELEROMETER_X, 0.12f)
            putFloat(Wire.ACCELEROMETER_Y, -0.03f)
            putFloat(Wire.ACCELEROMETER_Z, 9.81f)
            putBoolean(Wire.GYROSCOPE_AVAILABLE, true)
            putFloat(Wire.GYROSCOPE_X, 0.001f)
            putFloat(Wire.GYROSCOPE_Y, 0f)
            putFloat(Wire.GYROSCOPE_Z, -0.002f)
            putBoolean(Wire.HEART_RATE_AVAILABLE, true)
            putString(Wire.HEART_RATE_PERMISSION, SensorPermissionState.GRANTED.wireValue)
            putBoolean(Wire.HEART_RATE_BPM_PRESENT, true)
            putFloat(Wire.HEART_RATE_BPM, 72f)
        }

        val parsed = WearProtocolCodec.decodeWatchSnapshotResponse(map.toByteArray()).success()

        assertEquals(0.12f, parsed.accelerometerX!!, 0.0001f)
        assertEquals(-0.002f, parsed.gyroscopeZ!!, 0.0001f)
        assertEquals(72f, parsed.heartRateBpm!!, 0.0001f)
        assertEquals(1_700_000_000_330L, parsed.capturedAtEpochMs)
    }

    @Test
    fun oversizedPayloadIsRejected() {
        val oversized = envelope(route = WearProtocol.PATH_STATUS).apply {
            putString(Wire.BLOB, "x".repeat(WearProtocol.MAX_PAYLOAD_BYTES * 2))
        }
        // El codec publico valida el tamano antes de deserializar.
        val result = WearProtocolCodec.decodeWatchStatusResponse(oversized.toByteArray())
        assertTrue(result is WearDecodeResult.Failure)
        assertTrue(((result as WearDecodeResult.Failure).error) is WearProtocolError.PayloadTooLarge)
    }

    @Test
    fun corruptPayloadIsRejected() {
        val corrupt = ByteArray(256) { (it * 31).toByte() }
        val result = WearProtocolCodec.decodeWatchStatusResponse(corrupt)
        assertTrue(result is WearDecodeResult.Failure)
        assertTrue(((result as WearDecodeResult.Failure).error) is WearProtocolError.CorruptPayload)
    }

    private fun envelope(
        route: String = WearProtocol.PATH_HANDSHAKE,
        protocolVersion: Int = WearProtocol.PROTOCOL_VERSION,
        schemaVersion: Int = WearProtocol.SCHEMA_VERSION,
        requestId: String = "req-roundtrip"
    ): DataMap = DataMap().apply {
        putString(Wire.ROUTE, route)
        putInt(Wire.PROTOCOL_VERSION, protocolVersion)
        putInt(Wire.SCHEMA_VERSION, schemaVersion)
        putString(Wire.REQUEST_ID, requestId)
    }

    private fun <T> WearDecodeResult<T>.success(): T =
        (this as? WearDecodeResult.Success<T>)?.value
            ?: error("Expected Success but was $this")
}