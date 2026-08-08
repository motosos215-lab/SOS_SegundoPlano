package com.example.sos_segundoplano.wearprotocol

import com.google.android.gms.wearable.DataMap

/**
 * Resultado de decodificar un payload de respuesta. Un payload nunca se entrega a una capa
 * superior si su validación falla.
 */
sealed interface WearDecodeResult<out T> {
    data class Success<T>(val value: T) : WearDecodeResult<T>
    data class Failure(val error: WearProtocolError) : WearDecodeResult<Nothing>
}

/**
 * Codec del contrato móvil–Wear V1. Codifica solicitudes y decodifica respuestas usando
 * [DataMap] (`DataMap.toByteArray()` / `DataMap.fromByteArray()`). No usa serialización
 * Java ni Parcelable como contrato remoto.
 *
 * Rechaza un payload por:
 *  - payload excesivamente grande;
 *  - payload corrupto;
 *  - respuesta de otra ruta;
 *  - `protocolVersion` o `schemaVersion` incompatibles;
 *  - `requestId` vacío;
 *  - campo obligatorio ausente;
 *  - enum desconocido;
 *  - batería fuera de 0–100;
 *  - valores NaN o infinitos;
 *  - timestamp inválido;
 *  - secuencia negativa.
 *
 * La correlación del `requestId` contra la solicitud emitida la realiza la capa de datos.
 *
 * Las variantes `*Map` operan sobre un [DataMap] directamente y permiten probar la
 * semántica del contrato en unit tests JVM (la serialización a bytes requiere el runtime
 * de Android y se cubre en pruebas instrumentadas).
 */
object WearProtocolCodec {
    private object Keys {
        const val ROUTE = "route"
        const val PROTOCOL_VERSION = "protocolVersion"
        const val SCHEMA_VERSION = "schemaVersion"
        const val REQUEST_ID = "requestId"
        const val TIMESTAMP_EPOCH_MS = "timestamp"
        const val RESULT = "result"

        const val APP_VERSION_NAME = "appVersionName"
        const val APP_VERSION_CODE = "appVersionCode"
        const val MANUFACTURER = "manufacturer"
        const val MODEL = "model"
        const val WEAR_OS_API_LEVEL = "wearOsApiLevel"
        const val CAPABILITY = "capability"
        const val RESPONDED_AT_EPOCH_MS = "respondedAtEpochMs"

        const val SEQUENCE = "sequence"
        const val BATTERY_AVAILABLE = "batteryAvailable"
        const val BATTERY_PERCENT = "batteryPercent"
        const val CHARGING_KNOWN = "chargingKnown"
        const val CHARGING = "charging"
        const val ACCELEROMETER_AVAILABLE = "accelerometerAvailable"
        const val GYROSCOPE_AVAILABLE = "gyroscopeAvailable"
        const val HEART_RATE_AVAILABLE = "heartRateAvailable"
        const val HEART_RATE_PERMISSION = "heartRatePermission"
        const val CAPTURED_AT_EPOCH_MS = "capturedAtEpochMs"
        const val ACCELEROMETER_X = "accelerometerX"
        const val ACCELEROMETER_Y = "accelerometerY"
        const val ACCELEROMETER_Z = "accelerometerZ"
        const val GYROSCOPE_X = "gyroscopeX"
        const val GYROSCOPE_Y = "gyroscopeY"
        const val GYROSCOPE_Z = "gyroscopeZ"
        const val HEART_RATE_BPM_PRESENT = "heartRateBpmPresent"
        const val HEART_RATE_BPM = "heartRateBpm"
    }

    // -------------------------------------------------------------- encoding

    fun encodeHandshakeRequest(request: HandshakeRequest): ByteArray =
        encodeHandshakeRequestMap(request).toByteArray()

    fun encodeWatchStatusRequest(request: WatchStatusRequest): ByteArray =
        encodeWatchStatusRequestMap(request).toByteArray()

    fun encodeWatchSnapshotRequest(request: WatchSnapshotRequest): ByteArray =
        encodeWatchSnapshotRequestMap(request).toByteArray()

    internal fun encodeHandshakeRequestMap(request: HandshakeRequest): DataMap =
        encodeRequest(WearProtocol.PATH_HANDSHAKE, request.requestId, request.timestampEpochMs)

    internal fun encodeWatchStatusRequestMap(request: WatchStatusRequest): DataMap =
        encodeRequest(WearProtocol.PATH_STATUS, request.requestId, request.timestampEpochMs)

    internal fun encodeWatchSnapshotRequestMap(request: WatchSnapshotRequest): DataMap =
        encodeRequest(WearProtocol.PATH_SNAPSHOT, request.requestId, request.timestampEpochMs)

    private fun encodeRequest(route: String, requestId: String, timestampEpochMs: Long): DataMap = DataMap().apply {
        putString(Keys.ROUTE, route)
        putInt(Keys.PROTOCOL_VERSION, WearProtocol.PROTOCOL_VERSION)
        putInt(Keys.SCHEMA_VERSION, WearProtocol.SCHEMA_VERSION)
        putString(Keys.REQUEST_ID, requestId)
        putLong(Keys.TIMESTAMP_EPOCH_MS, timestampEpochMs)
    }

    // -------------------------------------------------------------- decoding (bytes)

    fun decodeHandshakeResponse(bytes: ByteArray): WearDecodeResult<HandshakeResponse> {
        val map = fromBytes(bytes) ?: return WearDecodeResult.Failure(fromBytesError(bytes))
        return decodeHandshakeResponseMap(map)
    }

    fun decodeWatchStatusResponse(bytes: ByteArray): WearDecodeResult<WatchStatusResponse> {
        val map = fromBytes(bytes) ?: return WearDecodeResult.Failure(fromBytesError(bytes))
        return decodeWatchStatusResponseMap(map)
    }

    fun decodeWatchSnapshotResponse(bytes: ByteArray): WearDecodeResult<WatchSnapshotResponse> {
        val map = fromBytes(bytes) ?: return WearDecodeResult.Failure(fromBytesError(bytes))
        return decodeWatchSnapshotResponseMap(map)
    }

    private fun fromBytes(bytes: ByteArray): DataMap? {
        if (bytes.size > WearProtocol.MAX_PAYLOAD_BYTES) return null
        return try {
            DataMap.fromByteArray(bytes)
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun fromBytesError(bytes: ByteArray): WearProtocolError =
        if (bytes.size > WearProtocol.MAX_PAYLOAD_BYTES) WearProtocolError.PayloadTooLarge
        else WearProtocolError.CorruptPayload

    // -------------------------------------------------------------- decoding (map)

    internal fun decodeHandshakeResponseMap(map: DataMap): WearDecodeResult<HandshakeResponse> {
        val envelope = when (val frame = checkFrame(map, WearProtocol.PATH_HANDSHAKE)) {
            is Frame.Ready -> frame.envelope
            is Frame.Failure -> return WearDecodeResult.Failure(frame.error)
        }

        val result = WearRpcResult.fromWire(map.getString(Keys.RESULT))
            ?: return WearDecodeResult.Failure(WearProtocolError.InvalidEnumValue(Keys.RESULT, map.getString(Keys.RESULT)))
        val appVersionName = nonBlank(map.getString(Keys.APP_VERSION_NAME))
            ?: return WearDecodeResult.Failure(WearProtocolError.RequiredFieldMissing(Keys.APP_VERSION_NAME))
        val manufacturer = nonBlank(map.getString(Keys.MANUFACTURER))
            ?: return WearDecodeResult.Failure(WearProtocolError.RequiredFieldMissing(Keys.MANUFACTURER))
        val model = nonBlank(map.getString(Keys.MODEL))
            ?: return WearDecodeResult.Failure(WearProtocolError.RequiredFieldMissing(Keys.MODEL))
        val capability = nonBlank(map.getString(Keys.CAPABILITY))
            ?: return WearDecodeResult.Failure(WearProtocolError.RequiredFieldMissing(Keys.CAPABILITY))
        if (capability != WearProtocol.CAPABILITY_WEAR_TELEMETRY) {
            return WearDecodeResult.Failure(WearProtocolError.InvalidCapability(WearProtocol.CAPABILITY_WEAR_TELEMETRY, capability))
        }
        if (!map.containsKey(Keys.APP_VERSION_CODE)) {
            return WearDecodeResult.Failure(WearProtocolError.RequiredFieldMissing(Keys.APP_VERSION_CODE))
        }
        if (!map.containsKey(Keys.WEAR_OS_API_LEVEL)) {
            return WearDecodeResult.Failure(WearProtocolError.RequiredFieldMissing(Keys.WEAR_OS_API_LEVEL))
        }
        val respondedAt = map.getLong(Keys.RESPONDED_AT_EPOCH_MS)
        if (respondedAt <= 0L) {
            return WearDecodeResult.Failure(WearProtocolError.InvalidTimestamp(Keys.RESPONDED_AT_EPOCH_MS, respondedAt))
        }

        return WearDecodeResult.Success(
            HandshakeResponse(
                protocolVersion = envelope.protocolVersion,
                schemaVersion = envelope.schemaVersion,
                requestId = envelope.requestId,
                result = result,
                appVersionName = appVersionName,
                appVersionCode = map.getLong(Keys.APP_VERSION_CODE),
                manufacturer = manufacturer,
                model = model,
                wearOsApiLevel = map.getInt(Keys.WEAR_OS_API_LEVEL),
                capability = capability,
                respondedAtEpochMs = respondedAt
            )
        )
    }

    internal fun decodeWatchStatusResponseMap(map: DataMap): WearDecodeResult<WatchStatusResponse> {
        val envelope = when (val frame = checkFrame(map, WearProtocol.PATH_STATUS)) {
            is Frame.Ready -> frame.envelope
            is Frame.Failure -> return WearDecodeResult.Failure(frame.error)
        }

        val result = WearRpcResult.fromWire(map.getString(Keys.RESULT))
            ?: return WearDecodeResult.Failure(WearProtocolError.InvalidEnumValue(Keys.RESULT, map.getString(Keys.RESULT)))
        val sequence = requireSequence(map) ?: return WearDecodeResult.Failure(map.sequenceError())
        if (!map.containsKey(Keys.BATTERY_AVAILABLE)) {
            return WearDecodeResult.Failure(WearProtocolError.RequiredFieldMissing(Keys.BATTERY_AVAILABLE))
        }
        val batteryAvailable = map.getBoolean(Keys.BATTERY_AVAILABLE)
        val batteryRead = if (batteryAvailable) readBattery(map) else BatteryRead.Absent
        if (batteryAvailable && batteryRead is BatteryRead.Absent) {
            return WearDecodeResult.Failure(WearProtocolError.RequiredFieldMissing(Keys.BATTERY_PERCENT))
        }
        if (batteryRead is BatteryRead.OutOfRange) {
            return WearDecodeResult.Failure(WearProtocolError.InvalidBatteryPercentage(map.getInt(Keys.BATTERY_PERCENT)))
        }
        if (!map.containsKey(Keys.CHARGING_KNOWN)) {
            return WearDecodeResult.Failure(WearProtocolError.RequiredFieldMissing(Keys.CHARGING_KNOWN))
        }
        val chargingKnown = map.getBoolean(Keys.CHARGING_KNOWN)
        val charging = when {
            chargingKnown && !map.containsKey(Keys.CHARGING) ->
                return WearDecodeResult.Failure(WearProtocolError.RequiredFieldMissing(Keys.CHARGING))
            chargingKnown -> map.getBoolean(Keys.CHARGING)
            else -> null
        }
        if (!map.containsKey(Keys.ACCELEROMETER_AVAILABLE)) {
            return WearDecodeResult.Failure(WearProtocolError.RequiredFieldMissing(Keys.ACCELEROMETER_AVAILABLE))
        }
        val accelerometerAvailable = map.getBoolean(Keys.ACCELEROMETER_AVAILABLE)
        if (!map.containsKey(Keys.GYROSCOPE_AVAILABLE)) {
            return WearDecodeResult.Failure(WearProtocolError.RequiredFieldMissing(Keys.GYROSCOPE_AVAILABLE))
        }
        val gyroscopeAvailable = map.getBoolean(Keys.GYROSCOPE_AVAILABLE)
        if (!map.containsKey(Keys.HEART_RATE_AVAILABLE)) {
            return WearDecodeResult.Failure(WearProtocolError.RequiredFieldMissing(Keys.HEART_RATE_AVAILABLE))
        }
        val heartRateAvailable = map.getBoolean(Keys.HEART_RATE_AVAILABLE)
        val heartRatePermission = readPermission(map)
            ?: return WearDecodeResult.Failure(WearProtocolError.InvalidEnumValue(Keys.HEART_RATE_PERMISSION, map.getString(Keys.HEART_RATE_PERMISSION)))
        val respondedAt = map.getLong(Keys.RESPONDED_AT_EPOCH_MS)
        if (respondedAt <= 0L) {
            return WearDecodeResult.Failure(WearProtocolError.InvalidTimestamp(Keys.RESPONDED_AT_EPOCH_MS, respondedAt))
        }

        return WearDecodeResult.Success(
            WatchStatusResponse(
                protocolVersion = envelope.protocolVersion,
                schemaVersion = envelope.schemaVersion,
                requestId = envelope.requestId,
                result = result,
                sequence = sequence,
                batteryAvailable = batteryAvailable,
                batteryPercent = (batteryRead as? BatteryRead.Value)?.percent,
                chargingKnown = chargingKnown,
                charging = charging,
                accelerometerAvailable = accelerometerAvailable,
                gyroscopeAvailable = gyroscopeAvailable,
                heartRateAvailable = heartRateAvailable,
                heartRatePermission = heartRatePermission,
                respondedAtEpochMs = respondedAt
            )
        )
    }

    internal fun decodeWatchSnapshotResponseMap(map: DataMap): WearDecodeResult<WatchSnapshotResponse> {
        val envelope = when (val frame = checkFrame(map, WearProtocol.PATH_SNAPSHOT)) {
            is Frame.Ready -> frame.envelope
            is Frame.Failure -> return WearDecodeResult.Failure(frame.error)
        }

        val result = WearRpcResult.fromWire(map.getString(Keys.RESULT))
            ?: return WearDecodeResult.Failure(WearProtocolError.InvalidEnumValue(Keys.RESULT, map.getString(Keys.RESULT)))
        val sequence = requireSequence(map) ?: return WearDecodeResult.Failure(map.sequenceError())
        val capturedAt = map.getLong(Keys.CAPTURED_AT_EPOCH_MS)
        if (capturedAt <= 0L) {
            return WearDecodeResult.Failure(WearProtocolError.InvalidTimestamp(Keys.CAPTURED_AT_EPOCH_MS, capturedAt))
        }
        if (!map.containsKey(Keys.ACCELEROMETER_AVAILABLE)) {
            return WearDecodeResult.Failure(WearProtocolError.RequiredFieldMissing(Keys.ACCELEROMETER_AVAILABLE))
        }
        val accelerometerAvailable = map.getBoolean(Keys.ACCELEROMETER_AVAILABLE)
        val accelerometerX = when (val result = vectorRead(map, Keys.ACCELEROMETER_X, accelerometerAvailable)) {
            is WearDecodeResult.Success -> result.value
            is WearDecodeResult.Failure -> return WearDecodeResult.Failure(result.error)
        }
        val accelerometerY = when (val result = vectorRead(map, Keys.ACCELEROMETER_Y, accelerometerAvailable)) {
            is WearDecodeResult.Success -> result.value
            is WearDecodeResult.Failure -> return WearDecodeResult.Failure(result.error)
        }
        val accelerometerZ = when (val result = vectorRead(map, Keys.ACCELEROMETER_Z, accelerometerAvailable)) {
            is WearDecodeResult.Success -> result.value
            is WearDecodeResult.Failure -> return WearDecodeResult.Failure(result.error)
        }
        if (!map.containsKey(Keys.GYROSCOPE_AVAILABLE)) {
            return WearDecodeResult.Failure(WearProtocolError.RequiredFieldMissing(Keys.GYROSCOPE_AVAILABLE))
        }
        val gyroscopeAvailable = map.getBoolean(Keys.GYROSCOPE_AVAILABLE)
        val gyroscopeX = when (val result = vectorRead(map, Keys.GYROSCOPE_X, gyroscopeAvailable)) {
            is WearDecodeResult.Success -> result.value
            is WearDecodeResult.Failure -> return WearDecodeResult.Failure(result.error)
        }
        val gyroscopeY = when (val result = vectorRead(map, Keys.GYROSCOPE_Y, gyroscopeAvailable)) {
            is WearDecodeResult.Success -> result.value
            is WearDecodeResult.Failure -> return WearDecodeResult.Failure(result.error)
        }
        val gyroscopeZ = when (val result = vectorRead(map, Keys.GYROSCOPE_Z, gyroscopeAvailable)) {
            is WearDecodeResult.Success -> result.value
            is WearDecodeResult.Failure -> return WearDecodeResult.Failure(result.error)
        }
        if (!map.containsKey(Keys.HEART_RATE_AVAILABLE)) {
            return WearDecodeResult.Failure(WearProtocolError.RequiredFieldMissing(Keys.HEART_RATE_AVAILABLE))
        }
        val heartRateAvailable = map.getBoolean(Keys.HEART_RATE_AVAILABLE)
        val heartRatePermission = readPermission(map)
            ?: return WearDecodeResult.Failure(WearProtocolError.InvalidEnumValue(Keys.HEART_RATE_PERMISSION, map.getString(Keys.HEART_RATE_PERMISSION)))
        if (!map.containsKey(Keys.HEART_RATE_BPM_PRESENT)) {
            return WearDecodeResult.Failure(WearProtocolError.RequiredFieldMissing(Keys.HEART_RATE_BPM_PRESENT))
        }
        val heartRateBpmPresent = map.getBoolean(Keys.HEART_RATE_BPM_PRESENT)
        val heartRateBpm = when {
            !heartRateBpmPresent -> null
            !map.containsKey(Keys.HEART_RATE_BPM) ->
                return WearDecodeResult.Failure(WearProtocolError.RequiredFieldMissing(Keys.HEART_RATE_BPM))
            else -> map.getFloat(Keys.HEART_RATE_BPM).also { bpm ->
                if (!bpm.isFinite()) {
                    return WearDecodeResult.Failure(WearProtocolError.NonFiniteValue(Keys.HEART_RATE_BPM))
                }
                if (bpm < 0f) {
                    return WearDecodeResult.Failure(WearProtocolError.InvalidHeartRateBpm(bpm))
                }
            }
        }

        return WearDecodeResult.Success(
            WatchSnapshotResponse(
                protocolVersion = envelope.protocolVersion,
                schemaVersion = envelope.schemaVersion,
                requestId = envelope.requestId,
                result = result,
                sequence = sequence,
                capturedAtEpochMs = capturedAt,
                accelerometerAvailable = accelerometerAvailable,
                accelerometerX = accelerometerX,
                accelerometerY = accelerometerY,
                accelerometerZ = accelerometerZ,
                gyroscopeAvailable = gyroscopeAvailable,
                gyroscopeX = gyroscopeX,
                gyroscopeY = gyroscopeY,
                gyroscopeZ = gyroscopeZ,
                heartRateAvailable = heartRateAvailable,
                heartRatePermission = heartRatePermission,
                heartRateBpmPresent = heartRateBpmPresent,
                heartRateBpm = heartRateBpm
            )
        )
    }

    // -------------------------------------------------------------- read primitives

    private data class Envelope(val protocolVersion: Int, val schemaVersion: Int, val requestId: String)

    private sealed interface Frame {
        data class Ready(val map: DataMap, val envelope: Envelope) : Frame
        data class Failure(val error: WearProtocolError) : Frame
    }

    private sealed interface BatteryRead {
        data class Value(val percent: Int) : BatteryRead
        data object Absent : BatteryRead
        data object OutOfRange : BatteryRead
    }

    private fun checkFrame(map: DataMap, expectedRoute: String): Frame {
        val route = map.getString(Keys.ROUTE)
        if (route != expectedRoute) {
            return Frame.Failure(WearProtocolError.UnexpectedRoute(expectedRoute, route))
        }
        val protocolVersion = map.getInt(Keys.PROTOCOL_VERSION, -1)
        if (protocolVersion != WearProtocol.PROTOCOL_VERSION) {
            return Frame.Failure(WearProtocolError.ProtocolVersionMismatch(WearProtocol.PROTOCOL_VERSION, protocolVersion))
        }
        val schemaVersion = map.getInt(Keys.SCHEMA_VERSION, -1)
        if (schemaVersion != WearProtocol.SCHEMA_VERSION) {
            return Frame.Failure(WearProtocolError.SchemaVersionMismatch(WearProtocol.SCHEMA_VERSION, schemaVersion))
        }
        val requestId = nonBlank(map.getString(Keys.REQUEST_ID))
            ?: return Frame.Failure(WearProtocolError.EmptyRequestId)
        return Frame.Ready(
            map,
            Envelope(protocolVersion = protocolVersion, schemaVersion = schemaVersion, requestId = requestId)
        )
    }

    private fun nonBlank(value: String?): String? = value?.takeIf { it.isNotBlank() }

    private fun requireSequence(map: DataMap): Long? {
        if (!map.containsKey(Keys.SEQUENCE)) return null
        val sequence = map.getLong(Keys.SEQUENCE)
        return if (sequence < 0L) null else sequence
    }

    private fun DataMap.sequenceError(): WearProtocolError =
        if (containsKey(Keys.SEQUENCE)) WearProtocolError.InvalidSequence(getLong(Keys.SEQUENCE))
        else WearProtocolError.RequiredFieldMissing(Keys.SEQUENCE)

    private fun readPermission(map: DataMap): SensorPermissionState? {
        val value = map.getString(Keys.HEART_RATE_PERMISSION) ?: return null
        return SensorPermissionState.fromWire(value)
    }

    private fun readBattery(map: DataMap): BatteryRead {
        if (!map.containsKey(Keys.BATTERY_PERCENT)) return BatteryRead.Absent
        val percent = map.getInt(Keys.BATTERY_PERCENT)
        return if (percent in 0..100) BatteryRead.Value(percent) else BatteryRead.OutOfRange
    }

    /** Valor de un componente vectorial. Devuelve `Success(null)` si el sensor no está disponible,
     *  `Success(valor)` si lo está y es finito, y rechaza con un error cuando el sensor sí está
     *  disponible pero el componente falta o no es finito. */
    private fun vectorRead(map: DataMap, key: String, available: Boolean): WearDecodeResult<Float?> {
        if (!available) return WearDecodeResult.Success(null)
        if (!map.containsKey(key)) return WearDecodeResult.Failure(WearProtocolError.RequiredFieldMissing(key))
        val value = map.getFloat(key)
        return if (value.isFinite()) WearDecodeResult.Success(value)
        else WearDecodeResult.Failure(WearProtocolError.NonFiniteValue(key))
    }
}
