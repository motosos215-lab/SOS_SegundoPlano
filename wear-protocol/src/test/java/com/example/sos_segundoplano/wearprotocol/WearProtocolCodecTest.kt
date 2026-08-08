package com.example.sos_segundoplano.wearprotocol

import com.google.android.gms.wearable.DataMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas JVM de la semántica del contrato V1. Operan a nivel de [DataMap] (variantes `*Map`)
 * porque la serialización a bytes requiere el runtime de Android y se cubre en
 * [WearProtocolCodecRoundTripTest] (instrumentado).
 *
 * Las claves se declaran localmente para verificar el contrato de cable de forma explícita.
 */
class WearProtocolCodecTest {

    private object Wire {
        const val ROUTE = "route"
        const val PROTOCOL_VERSION = "protocolVersion"
        const val SCHEMA_VERSION = "schemaVersion"
        const val REQUEST_ID = "requestId"
        const val TIMESTAMP = "timestamp"
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
    }

    private val requestId = "request-123"
    private val sentAt = 1_700_000_000_000L
    private val respondedAt = 1_700_000_000_110L

    // ----------------------------------------------------------------------- encoding

    @Test
    fun `encodeHandshakeRequestMap escribe ruta versiones requestId y timestamp`() {
        val map = WearProtocolCodec.encodeHandshakeRequestMap(
            HandshakeRequest(requestId = requestId, timestampEpochMs = sentAt)
        )
        assertEquals(WearProtocol.PATH_HANDSHAKE, map.getString(Wire.ROUTE))
        assertEquals(1, map.getInt(Wire.PROTOCOL_VERSION))
        assertEquals(1, map.getInt(Wire.SCHEMA_VERSION))
        assertEquals(requestId, map.getString(Wire.REQUEST_ID))
        assertEquals(sentAt, map.getLong(Wire.TIMESTAMP))
    }

    @Test
    fun `encodeWatchStatusRequestMap usa la ruta de status`() {
        val map = WearProtocolCodec.encodeWatchStatusRequestMap(
            WatchStatusRequest(requestId = requestId, timestampEpochMs = sentAt)
        )
        assertEquals(WearProtocol.PATH_STATUS, map.getString(Wire.ROUTE))
    }

    @Test
    fun `encodeWatchSnapshotRequestMap usa la ruta de snapshot`() {
        val map = WearProtocolCodec.encodeWatchSnapshotRequestMap(
            WatchSnapshotRequest(requestId = requestId, timestampEpochMs = sentAt)
        )
        assertEquals(WearProtocol.PATH_SNAPSHOT, map.getString(Wire.ROUTE))
    }

    // ----------------------------------------------------------------------- frame

    @Test
    fun `respuesta de otra ruta se rechaza como UnexpectedRoute`() {
        val result = WearProtocolCodec.decodeHandshakeResponseMap(
            envelope(route = WearProtocol.PATH_STATUS)
        )
        val error = result.failure()
        assertTrue(error is WearProtocolError.UnexpectedRoute)
    }

    @Test
    fun `protocolVersion distinta se rechaza como ProtocolVersionMismatch`() {
        val result = WearProtocolCodec.decodeWatchStatusResponseMap(
            envelope(route = WearProtocol.PATH_STATUS, protocolVersion = 2)
        )
        val error = result.failure()
        assertTrue(error is WearProtocolError.ProtocolVersionMismatch)
        assertEquals(WearProtocol.PROTOCOL_VERSION, (error as WearProtocolError.ProtocolVersionMismatch).expected)
        assertEquals(2, error.actual)
    }

    @Test
    fun `schemaVersion distinta se rechaza como SchemaVersionMismatch`() {
        val result = WearProtocolCodec.decodeWatchSnapshotResponseMap(
            envelope(route = WearProtocol.PATH_SNAPSHOT, schemaVersion = 2)
        )
        val error = result.failure()
        assertTrue(error is WearProtocolError.SchemaVersionMismatch)
    }

    @Test
    fun `requestId en blanco se rechaza como EmptyRequestId`() {
        val result = WearProtocolCodec.decodeHandshakeResponseMap(
            envelope(route = WearProtocol.PATH_HANDSHAKE, requestId = "   ")
        )
        val error = result.failure()
        assertTrue(error is WearProtocolError.EmptyRequestId)
    }

    // ----------------------------------------------------------------------- handshake

    @Test
    fun `decodeHandshakeResponseMap decodifica una respuesta valida`() {
        val response = WearProtocolCodec.decodeHandshakeResponseMap(
            handshakeMap(
                result = "ok",
                appVersionName = "MotoSOS Wear",
                appVersionCode = 3,
                manufacturer = "Samsung",
                model = "Galaxy Watch 6",
                wearOsApiLevel = 32,
                capability = WearProtocol.CAPABILITY_WEAR_TELEMETRY,
                respondedAt = respondedAt
            )
        )
        val parsed = response.success()
        assertEquals(requestId, parsed.requestId)
        assertEquals(WearRpcResult.OK, parsed.result)
        assertEquals("MotoSOS Wear", parsed.appVersionName)
        assertEquals(3L, parsed.appVersionCode)
        assertEquals("Samsung", parsed.manufacturer)
        assertEquals("Galaxy Watch 6", parsed.model)
        assertEquals(32, parsed.wearOsApiLevel)
        assertEquals(WearProtocol.CAPABILITY_WEAR_TELEMETRY, parsed.capability)
        assertEquals(respondedAt, parsed.respondedAtEpochMs)
    }

    @Test
    fun `handshake con resultado desconocido se rechaza como InvalidEnumValue`() {
        val result = WearProtocolCodec.decodeHandshakeResponseMap(
            handshakeMap(result = "mystery")
        )
        val error = result.failure()
        assertTrue(error is WearProtocolError.InvalidEnumValue)
    }

    @Test
    fun `handshake sin appVersionName se rechaza como RequiredFieldMissing`() {
        val result = WearProtocolCodec.decodeHandshakeResponseMap(
            handshakeMap(appVersionName = "  ")
        )
        val error = result.failure()
        assertTrue(error is WearProtocolError.RequiredFieldMissing)
    }

    @Test
    fun `handshake sin modelo se rechaza como RequiredFieldMissing`() {
        val result = WearProtocolCodec.decodeHandshakeResponseMap(
            handshakeMap(model = null)
        )
        val error = result.failure()
        assertTrue(error is WearProtocolError.RequiredFieldMissing)
    }

    @Test
    fun `handshake sin appVersionCode se rechaza como RequiredFieldMissing`() {
        val result = WearProtocolCodec.decodeHandshakeResponseMap(
            handshakeMap().apply { remove(Wire.APP_VERSION_CODE) }
        )
        val error = result.failure()
        assertTrue(error is WearProtocolError.RequiredFieldMissing)
    }

    @Test
    fun `handshake sin wearOsApiLevel se rechaza como RequiredFieldMissing`() {
        val result = WearProtocolCodec.decodeHandshakeResponseMap(
            handshakeMap().apply { remove(Wire.WEAR_OS_API_LEVEL) }
        )
        val error = result.failure()
        assertTrue(error is WearProtocolError.RequiredFieldMissing)
    }

    @Test
    fun `handshake con respondedAt no positivo se rechaza como InvalidTimestamp`() {
        val result = WearProtocolCodec.decodeHandshakeResponseMap(
            handshakeMap(respondedAt = 0L)
        )
        val error = result.failure()
        assertTrue(error is WearProtocolError.InvalidTimestamp)
    }

    @Test
    fun `handshake con capability inesperada se rechaza como InvalidCapability`() {
        val result = WearProtocolCodec.decodeHandshakeResponseMap(
            handshakeMap(capability = "other_capability")
        )
        val error = result.failure()
        assertTrue(error is WearProtocolError.InvalidCapability)
    }

    // ----------------------------------------------------------------------- status

    @Test
    fun `decodeWatchStatusResponseMap decodifica una respuesta valida con bateria`() {
        val response = WearProtocolCodec.decodeWatchStatusResponseMap(
            statusMap(
                sequence = 7L,
                batteryAvailable = true,
                batteryPercent = 87,
                chargingKnown = true,
                charging = false,
                permission = "granted",
                respondedAt = respondedAt
            )
        )
        val parsed = response.success()
        assertEquals(WearRpcResult.OK, parsed.result)
        assertEquals(7L, parsed.sequence)
        assertTrue(parsed.batteryAvailable)
        assertEquals(87, parsed.batteryPercent)
        assertTrue(parsed.chargingKnown)
        assertEquals(false, parsed.charging)
        assertTrue(parsed.accelerometerAvailable)
        assertTrue(parsed.gyroscopeAvailable)
        assertTrue(parsed.heartRateAvailable)
        assertEquals(SensorPermissionState.GRANTED, parsed.heartRatePermission)
        assertEquals(respondedAt, parsed.respondedAtEpochMs)
    }

    @Test
    fun `status con bateria fuera de rango se rechaza como InvalidBatteryPercentage`() {
        val result = WearProtocolCodec.decodeWatchStatusResponseMap(
            statusMap(batteryAvailable = true, batteryPercent = 101)
        )
        val error = result.failure()
        assertTrue(error is WearProtocolError.InvalidBatteryPercentage)
    }

    @Test
    fun `status sin bateria deja batteryPercent en null`() {
        val parsed = WearProtocolCodec.decodeWatchStatusResponseMap(
            statusMap(batteryAvailable = false)
        ).success()
        assertEquals(false, parsed.batteryAvailable)
        assertNull(parsed.batteryPercent)
    }

    @Test
    fun `status con bateria disponible sin porcentaje se rechaza como RequiredFieldMissing`() {
        val result = WearProtocolCodec.decodeWatchStatusResponseMap(
            statusMap(batteryAvailable = true, batteryPercent = null)
        )
        val error = result.failure()
        assertTrue(error is WearProtocolError.RequiredFieldMissing)
    }

    @Test
    fun `status con chargingKnown sin campo charging se rechaza como RequiredFieldMissing`() {
        val result = WearProtocolCodec.decodeWatchStatusResponseMap(
            statusMap(chargingKnown = true, charging = null)
        )
        val error = result.failure()
        assertTrue(error is WearProtocolError.RequiredFieldMissing)
    }

    @Test
    fun `status sin accelerometerAvailable se rechaza como RequiredFieldMissing`() {
        val result = WearProtocolCodec.decodeWatchStatusResponseMap(
            statusMap().apply { remove(Wire.ACCELEROMETER_AVAILABLE) }
        )
        val error = result.failure()
        assertTrue(error is WearProtocolError.RequiredFieldMissing)
    }

    @Test
    fun `status con permiso de ritmo cardiaco invalido se rechaza como InvalidEnumValue`() {
        val result = WearProtocolCodec.decodeWatchStatusResponseMap(
            statusMap(permission = "granted ")

        )
        val error = result.failure()
        assertTrue(error is WearProtocolError.InvalidEnumValue)
    }

    @Test
    fun `status sin sequence se rechaza como RequiredFieldMissing`() {
        val result = WearProtocolCodec.decodeWatchStatusResponseMap(
            statusMap().apply { remove(Wire.SEQUENCE) }
        )
        val error = result.failure()
        assertTrue(error is WearProtocolError.RequiredFieldMissing)
    }

    @Test
    fun `status con sequence negativo se rechaza como InvalidSequence`() {
        val result = WearProtocolCodec.decodeWatchStatusResponseMap(
            statusMap().apply { putLong(Wire.SEQUENCE, -1L) }
        )
        val error = result.failure()
        assertTrue(error is WearProtocolError.InvalidSequence)
    }

    @Test
    fun `status con respondedAt no positivo se rechaza como InvalidTimestamp`() {
        val result = WearProtocolCodec.decodeWatchStatusResponseMap(
            statusMap(respondedAt = -5L)
        )
        val error = result.failure()
        assertTrue(error is WearProtocolError.InvalidTimestamp)
    }

    // ----------------------------------------------------------------------- snapshot

    @Test
    fun `decodeWatchSnapshotResponseMap decodifica una respuesta valida con todos los sensores`() {
        val parsed = WearProtocolCodec.decodeWatchSnapshotResponseMap(
            snapshotMap(
                capturedAt = respondedAt,
                accelAvailable = true,
                gyroAvailable = true,
                heartAvailable = true,
                permission = "granted",
                bpmPresent = true,
                bpm = 72f
            )
        ).success()

        assertEquals(WearRpcResult.OK, parsed.result)
        assertTrue(parsed.accelerometerAvailable)
        assertEquals(0.12f, parsed.accelerometerX!!, 0.0001f)
        assertEquals(-0.03f, parsed.accelerometerY!!, 0.0001f)
        assertEquals(9.81f, parsed.accelerometerZ!!, 0.0001f)
        assertTrue(parsed.gyroscopeAvailable)
        assertEquals(0.001f, parsed.gyroscopeX!!, 0.0001f)
        assertEquals(0.0f, parsed.gyroscopeY!!, 0.0001f)
        assertEquals(-0.002f, parsed.gyroscopeZ!!, 0.0001f)
        assertTrue(parsed.heartRateAvailable)
        assertEquals(SensorPermissionState.GRANTED, parsed.heartRatePermission)
        assertTrue(parsed.heartRateBpmPresent)
        assertEquals(72f, parsed.heartRateBpm!!, 0.0001f)
    }

    @Test
    fun `snapshot con sensores no disponibles deja vectores y bpm en null`() {
        val parsed = WearProtocolCodec.decodeWatchSnapshotResponseMap(
            snapshotMap(
                capturedAt = respondedAt,
                accelAvailable = false,
                gyroAvailable = false,
                heartAvailable = false,
                bpmPresent = false
            )
        ).success()

        assertEquals(false, parsed.accelerometerAvailable)
        assertNull(parsed.accelerometerX)
        assertEquals(false, parsed.gyroscopeAvailable)
        assertNull(parsed.gyroscopeY)
        assertEquals(false, parsed.heartRateAvailable)
        assertNull(parsed.heartRateBpm)
    }

    @Test
    fun `snapshot con acelerometro disponible pero componente ausente se rechaza`() {
        val result = WearProtocolCodec.decodeWatchSnapshotResponseMap(
            snapshotMap(capturedAt = respondedAt, accelAvailable = true).apply {
                remove(Wire.ACCELEROMETER_Y)
            }
        )
        val error = result.failure()
        assertTrue(error is WearProtocolError.RequiredFieldMissing)
    }

    @Test
    fun `snapshot con componente no finito se rechaza como NonFiniteValue`() {
        val result = WearProtocolCodec.decodeWatchSnapshotResponseMap(
            snapshotMap(capturedAt = respondedAt, accelAvailable = true).apply {
                putFloat(Wire.ACCELEROMETER_X, Float.NaN)
            }
        )
        val error = result.failure()
        assertTrue(error is WearProtocolError.NonFiniteValue)
    }

    @Test
    fun `snapshot con gira disponible pero componente ausente se rechaza`() {
        val result = WearProtocolCodec.decodeWatchSnapshotResponseMap(
            snapshotMap(capturedAt = respondedAt, gyroAvailable = true).apply {
                remove(Wire.GYROSCOPE_Z)
            }
        )
        val error = result.failure()
        assertTrue(error is WearProtocolError.RequiredFieldMissing)
    }

    @Test
    fun `snapshot con bpm presente pero sin valor se rechaza como RequiredFieldMissing`() {
        val result = WearProtocolCodec.decodeWatchSnapshotResponseMap(
            snapshotMap(capturedAt = respondedAt, bpmPresent = true).apply {
                remove(Wire.HEART_RATE_BPM)
            }
        )
        val error = result.failure()
        assertTrue(error is WearProtocolError.RequiredFieldMissing)
    }

    @Test
    fun `snapshot sin indicador bpmPresent se rechaza como RequiredFieldMissing`() {
        val result = WearProtocolCodec.decodeWatchSnapshotResponseMap(
            snapshotMap(capturedAt = respondedAt).apply {
                remove(Wire.HEART_RATE_BPM_PRESENT)
            }
        )
        val error = result.failure()
        assertTrue(error is WearProtocolError.RequiredFieldMissing)
    }

    @Test
    fun `snapshot con bpm no finito se rechaza como NonFiniteValue`() {
        val result = WearProtocolCodec.decodeWatchSnapshotResponseMap(
            snapshotMap(capturedAt = respondedAt, bpmPresent = true, bpm = Float.POSITIVE_INFINITY)
        )
        val error = result.failure()
        assertTrue(error is WearProtocolError.NonFiniteValue)
    }

    @Test
    fun `snapshot con bpm negativo se rechaza como InvalidHeartRateBpm`() {
        val result = WearProtocolCodec.decodeWatchSnapshotResponseMap(
            snapshotMap(capturedAt = respondedAt, bpmPresent = true, bpm = -1f)
        )
        val error = result.failure()
        assertTrue(error is WearProtocolError.InvalidHeartRateBpm)
    }

    @Test
    fun `snapshot con capturedAt no positivo se rechaza como InvalidTimestamp`() {
        val result = WearProtocolCodec.decodeWatchSnapshotResponseMap(
            snapshotMap(capturedAt = 0L)
        )
        val error = result.failure()
        assertTrue(error is WearProtocolError.InvalidTimestamp)
    }

    // ----------------------------------------------------------------------- builders

    private fun envelope(
        route: String = WearProtocol.PATH_HANDSHAKE,
        protocolVersion: Int = WearProtocol.PROTOCOL_VERSION,
        schemaVersion: Int = WearProtocol.SCHEMA_VERSION,
        requestId: String = this.requestId
    ): DataMap = DataMap().apply {
        putString(Wire.ROUTE, route)
        putInt(Wire.PROTOCOL_VERSION, protocolVersion)
        putInt(Wire.SCHEMA_VERSION, schemaVersion)
        putString(Wire.REQUEST_ID, requestId)
    }

    private fun handshakeMap(
        result: String = WearRpcResult.OK.wireValue,
        appVersionName: String = "MotoSOS Wear",
        appVersionCode: Long = 3L,
        manufacturer: String = "Samsung",
        model: String? = "Galaxy Watch 6",
        wearOsApiLevel: Int = 32,
        capability: String = WearProtocol.CAPABILITY_WEAR_TELEMETRY,
        respondedAt: Long = this.respondedAt
    ): DataMap = envelope().apply {
        putString(Wire.RESULT, result)
        putString(Wire.APP_VERSION_NAME, appVersionName)
        putLong(Wire.APP_VERSION_CODE, appVersionCode)
        putString(Wire.MANUFACTURER, manufacturer)
        if (model != null) putString(Wire.MODEL, model)
        putInt(Wire.WEAR_OS_API_LEVEL, wearOsApiLevel)
        putString(Wire.CAPABILITY, capability)
        putLong(Wire.RESPONDED_AT, respondedAt)
    }

    private fun statusMap(
        sequence: Long = 12L,
        batteryAvailable: Boolean = true,
        batteryPercent: Int? = 87,
        chargingKnown: Boolean = true,
        charging: Boolean? = false,
        accelerometerAvailable: Boolean = true,
        gyroscopeAvailable: Boolean = true,
        heartRateAvailable: Boolean = true,
        permission: String = SensorPermissionState.GRANTED.wireValue,
        respondedAt: Long = this.respondedAt
    ): DataMap = envelope(route = WearProtocol.PATH_STATUS).apply {
        putString(Wire.RESULT, WearRpcResult.OK.wireValue)
        putLong(Wire.SEQUENCE, sequence)
        putBoolean(Wire.BATTERY_AVAILABLE, batteryAvailable)
        if (batteryPercent != null) putInt(Wire.BATTERY_PERCENT, batteryPercent)
        putBoolean(Wire.CHARGING_KNOWN, chargingKnown)
        if (charging != null) putBoolean(Wire.CHARGING, charging)
        putBoolean(Wire.ACCELEROMETER_AVAILABLE, accelerometerAvailable)
        putBoolean(Wire.GYROSCOPE_AVAILABLE, gyroscopeAvailable)
        putBoolean(Wire.HEART_RATE_AVAILABLE, heartRateAvailable)
        putString(Wire.HEART_RATE_PERMISSION, permission)
        putLong(Wire.RESPONDED_AT, respondedAt)
    }

    private fun snapshotMap(
        capturedAt: Long,
        accelAvailable: Boolean = true,
        gyroAvailable: Boolean = true,
        heartAvailable: Boolean = true,
        permission: String = SensorPermissionState.GRANTED.wireValue,
        bpmPresent: Boolean = true,
        bpm: Float = 72f
    ): DataMap = envelope(route = WearProtocol.PATH_SNAPSHOT).apply {
        putString(Wire.RESULT, WearRpcResult.OK.wireValue)
        putLong(Wire.SEQUENCE, 3L)
        putLong(Wire.CAPTURED_AT, capturedAt)
        putBoolean(Wire.ACCELEROMETER_AVAILABLE, accelAvailable)
        putFloat(Wire.ACCELEROMETER_X, 0.12f)
        putFloat(Wire.ACCELEROMETER_Y, -0.03f)
        putFloat(Wire.ACCELEROMETER_Z, 9.81f)
        putBoolean(Wire.GYROSCOPE_AVAILABLE, gyroAvailable)
        putFloat(Wire.GYROSCOPE_X, 0.001f)
        putFloat(Wire.GYROSCOPE_Y, 0.0f)
        putFloat(Wire.GYROSCOPE_Z, -0.002f)
        putBoolean(Wire.HEART_RATE_AVAILABLE, heartAvailable)
        putString(Wire.HEART_RATE_PERMISSION, permission)
        putBoolean(Wire.HEART_RATE_BPM_PRESENT, bpmPresent)
        putFloat(Wire.HEART_RATE_BPM, bpm)
    }

    // ----------------------------------------------------------------------- helpers

    private fun <T> WearDecodeResult<T>.success(): T =
        (this as? WearDecodeResult.Success<T>)?.value
            ?: error("Expected Success but was $this")

    private fun <T> WearDecodeResult<T>.failure(): WearProtocolError =
        (this as? WearDecodeResult.Failure)?.error
            ?: error("Expected Failure but was $this")
}
