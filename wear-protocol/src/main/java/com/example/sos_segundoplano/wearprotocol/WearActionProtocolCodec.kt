package com.example.sos_segundoplano.wearprotocol

import com.google.android.gms.wearable.DataMap

/** Typed codec for phone-authoritative trip and SOS actions. */
object WearActionProtocolCodec {
    private const val ROUTE = "route"
    private const val PROTOCOL_VERSION = "protocolVersion"
    private const val SCHEMA_VERSION = "schemaVersion"
    private const val REQUEST_ID = "requestId"
    private const val COMMAND_ID = "commandId"
    private const val REQUESTED_AT = "requestedAtEpochMs"
    private const val RESPONDED_AT = "respondedAtEpochMs"
    private const val RESULT = "result"
    private const val CODE = "sanitizedCode"
    private const val REMOTE_TRIP_ID = "remoteTripId"
    private const val ACTIVE = "active"
    private const val STARTED_AT = "startedAtEpochMs"
    private const val UPDATED_AT = "updatedAtEpochMs"

    fun encodeTripStateRequest(value: TripStateRequest): ByteArray = encodeTripStateRequestMap(value).toByteArray()

    internal fun encodeTripStateRequestMap(value: TripStateRequest): DataMap = envelope(WearProtocol.PATH_TRIP_STATE, value.requestId).apply {
        putLong(REQUESTED_AT, value.requestedAtEpochMs)
    }

    fun encodeStartTripAction(value: StartTripActionRequest): ByteArray = encodeStartTripActionRequestMap(value).toByteArray()
    internal fun encodeStartTripActionRequestMap(value: StartTripActionRequest): DataMap = actionEnvelope(WearProtocol.PATH_ACTION_START_TRIP, value)
    fun encodeFinishTripAction(value: FinishTripActionRequest): ByteArray = encodeFinishTripActionRequestMap(value).toByteArray()
    internal fun encodeFinishTripActionRequestMap(value: FinishTripActionRequest): DataMap = actionEnvelope(WearProtocol.PATH_ACTION_FINISH_TRIP, value).apply { putString(REMOTE_TRIP_ID, value.remoteTripId) }
    fun encodeManualSosAction(value: ManualSosActionRequest): ByteArray = encodeManualSosActionRequestMap(value).toByteArray()
    internal fun encodeManualSosActionRequestMap(value: ManualSosActionRequest): DataMap = actionEnvelope(WearProtocol.PATH_ACTION_MANUAL_SOS, value).apply { putString(REMOTE_TRIP_ID, value.remoteTripId) }

    fun encodeTripStateResponse(value: TripStateResponse): ByteArray = encodeTripStateResponseMap(value).toByteArray()

    internal fun encodeTripStateResponseMap(value: TripStateResponse): DataMap = envelope(WearProtocol.PATH_TRIP_STATE, value.requestId).apply {
        putString(RESULT, value.result.wireValue); value.sanitizedCode?.let { putString(CODE, it) }
        putBoolean(ACTIVE, value.active); value.remoteTripId?.let { putString(REMOTE_TRIP_ID, it) }
        value.startedAtEpochMs?.let { putLong(STARTED_AT, it) }; putLong(UPDATED_AT, value.updatedAtEpochMs)
    }

    fun encodePhoneActionResponse(path: String, value: PhoneActionResponse): ByteArray = encodePhoneActionResponseMap(path, value).toByteArray()
    internal fun encodePhoneActionResponseMap(path: String, value: PhoneActionResponse): DataMap = envelope(path, value.requestId).apply {
        putString(RESULT, value.result.wireValue); value.sanitizedCode?.let { putString(CODE, it) }
        value.remoteTripId?.let { putString(REMOTE_TRIP_ID, it) }; putLong(RESPONDED_AT, value.respondedAtEpochMs)
    }

    fun decodeTripStateRequest(bytes: ByteArray): WearDecodeResult<TripStateRequest> {
        val map = fromBytes(bytes) ?: return WearDecodeResult.Failure(fromBytesError(bytes))
        return decodeTripStateRequestMap(map)
    }

    internal fun decodeTripStateRequestMap(map: DataMap): WearDecodeResult<TripStateRequest> = decodeMap(map, WearProtocol.PATH_TRIP_STATE) { _, id ->
        val timestamp = timestamp(map, REQUESTED_AT) ?: return@decodeMap failTimestamp(map, REQUESTED_AT)
        WearDecodeResult.Success(TripStateRequest(id, timestamp))
    }

    fun decodeStartTripAction(bytes: ByteArray): WearDecodeResult<StartTripActionRequest> {
        val map = fromBytes(bytes) ?: return WearDecodeResult.Failure(fromBytesError(bytes))
        return decodeStartTripActionRequestMap(map)
    }
    internal fun decodeStartTripActionRequestMap(map: DataMap): WearDecodeResult<StartTripActionRequest> = decodeActionMap(map, WearProtocol.PATH_ACTION_START_TRIP) { id, command, timestamp -> StartTripActionRequest(id, command, timestamp) }
    fun decodeFinishTripAction(bytes: ByteArray): WearDecodeResult<FinishTripActionRequest> {
        val map = fromBytes(bytes) ?: return WearDecodeResult.Failure(fromBytesError(bytes))
        return decodeFinishTripActionRequestMap(map)
    }
    internal fun decodeFinishTripActionRequestMap(map: DataMap): WearDecodeResult<FinishTripActionRequest> = decodeActionWithTripMap(map, WearProtocol.PATH_ACTION_FINISH_TRIP) { id, command, trip, timestamp -> FinishTripActionRequest(id, command, trip, timestamp) }
    fun decodeManualSosAction(bytes: ByteArray): WearDecodeResult<ManualSosActionRequest> {
        val map = fromBytes(bytes) ?: return WearDecodeResult.Failure(fromBytesError(bytes))
        return decodeManualSosActionRequestMap(map)
    }
    internal fun decodeManualSosActionRequestMap(map: DataMap): WearDecodeResult<ManualSosActionRequest> = decodeActionWithTripMap(map, WearProtocol.PATH_ACTION_MANUAL_SOS) { id, command, trip, timestamp -> ManualSosActionRequest(id, command, trip, timestamp) }

    fun decodeTripStateResponse(bytes: ByteArray): WearDecodeResult<TripStateResponse> {
        val map = fromBytes(bytes) ?: return WearDecodeResult.Failure(fromBytesError(bytes))
        return decodeTripStateResponseMap(map)
    }

    internal fun decodeTripStateResponseMap(map: DataMap): WearDecodeResult<TripStateResponse> = decodeMap(map, WearProtocol.PATH_TRIP_STATE) { _, id ->
        val result = PhoneActionResult.fromWire(map.getString(RESULT)) ?: return@decodeMap WearDecodeResult.Failure(WearProtocolError.InvalidEnumValue(RESULT, map.getString(RESULT)))
        if (!map.containsKey(ACTIVE)) return@decodeMap WearDecodeResult.Failure(WearProtocolError.RequiredFieldMissing(ACTIVE))
        val updated = timestamp(map, UPDATED_AT) ?: return@decodeMap failTimestamp(map, UPDATED_AT)
        val active = map.getBoolean(ACTIVE); val trip = map.getString(REMOTE_TRIP_ID)?.takeIf { it.isNotBlank() }
        if (active && trip == null) return@decodeMap WearDecodeResult.Failure(WearProtocolError.RequiredFieldMissing(REMOTE_TRIP_ID))
        WearDecodeResult.Success(TripStateResponse(id, active, trip, map.getLong(STARTED_AT).takeIf { it > 0L }, updated, result, map.getString(CODE)))
    }

    fun decodePhoneActionResponse(bytes: ByteArray, expectedPath: String): WearDecodeResult<PhoneActionResponse> {
        val map = fromBytes(bytes) ?: return WearDecodeResult.Failure(fromBytesError(bytes))
        return decodePhoneActionResponseMap(map, expectedPath)
    }
    internal fun decodePhoneActionResponseMap(map: DataMap, expectedPath: String): WearDecodeResult<PhoneActionResponse> = decodeMap(map, expectedPath) { _, id ->
        val result = PhoneActionResult.fromWire(map.getString(RESULT)) ?: return@decodeMap WearDecodeResult.Failure(WearProtocolError.InvalidEnumValue(RESULT, map.getString(RESULT)))
        val responded = timestamp(map, RESPONDED_AT) ?: return@decodeMap failTimestamp(map, RESPONDED_AT)
        WearDecodeResult.Success(PhoneActionResponse(id, result, map.getString(CODE), map.getString(REMOTE_TRIP_ID)?.takeIf { it.isNotBlank() }, responded))
    }

    private fun actionEnvelope(path: String, value: PhoneActionRequest) = envelope(path, value.requestId).apply { putString(COMMAND_ID, value.commandId); putLong(REQUESTED_AT, value.requestedAtEpochMs) }
    private fun envelope(path: String, requestId: String) = DataMap().apply { putString(ROUTE, path); putInt(PROTOCOL_VERSION, WearProtocol.PROTOCOL_VERSION); putInt(SCHEMA_VERSION, WearProtocol.SCHEMA_VERSION); putString(REQUEST_ID, requestId) }
    private fun <T> decode(bytes: ByteArray, expectedPath: String, parse: (DataMap, String) -> WearDecodeResult<T>): WearDecodeResult<T> {
        val map = fromBytes(bytes) ?: return WearDecodeResult.Failure(fromBytesError(bytes))
        return decodeMap(map, expectedPath, parse)
    }
    private fun <T> decodeMap(map: DataMap, expectedPath: String, parse: (DataMap, String) -> WearDecodeResult<T>): WearDecodeResult<T> {
        if (map.getString(ROUTE) != expectedPath) return WearDecodeResult.Failure(WearProtocolError.UnexpectedRoute(expectedPath, map.getString(ROUTE)))
        if (map.getInt(PROTOCOL_VERSION, -1) != WearProtocol.PROTOCOL_VERSION) return WearDecodeResult.Failure(WearProtocolError.ProtocolVersionMismatch(WearProtocol.PROTOCOL_VERSION, map.getInt(PROTOCOL_VERSION, -1)))
        if (map.getInt(SCHEMA_VERSION, -1) != WearProtocol.SCHEMA_VERSION) return WearDecodeResult.Failure(WearProtocolError.SchemaVersionMismatch(WearProtocol.SCHEMA_VERSION, map.getInt(SCHEMA_VERSION, -1)))
        val requestId = map.getString(REQUEST_ID)?.takeIf { it.isNotBlank() } ?: return WearDecodeResult.Failure(WearProtocolError.EmptyRequestId)
        return parse(map, requestId)
    }
    private fun fromBytes(bytes: ByteArray): DataMap? {
        if (bytes.size > WearProtocol.MAX_PAYLOAD_BYTES) return null
        return try { DataMap.fromByteArray(bytes) } catch (_: RuntimeException) { null }
    }
    private fun fromBytesError(bytes: ByteArray): WearProtocolError = if (bytes.size > WearProtocol.MAX_PAYLOAD_BYTES) WearProtocolError.PayloadTooLarge else WearProtocolError.CorruptPayload
    private fun <T> decodeAction(bytes: ByteArray, path: String, build: (String, String, Long) -> T): WearDecodeResult<T> = decode(bytes, path) { map, id ->
        val command = map.getString(COMMAND_ID)?.takeIf { it.isNotBlank() } ?: return@decode WearDecodeResult.Failure(WearProtocolError.RequiredFieldMissing(COMMAND_ID))
        val timestamp = timestamp(map, REQUESTED_AT) ?: return@decode failTimestamp(map, REQUESTED_AT); WearDecodeResult.Success(build(id, command, timestamp))
    }
    private fun <T> decodeActionMap(map: DataMap, path: String, build: (String, String, Long) -> T): WearDecodeResult<T> = decodeMap(map, path) { _, id ->
        val command = map.getString(COMMAND_ID)?.takeIf { it.isNotBlank() } ?: return@decodeMap WearDecodeResult.Failure(WearProtocolError.RequiredFieldMissing(COMMAND_ID))
        val timestamp = timestamp(map, REQUESTED_AT) ?: return@decodeMap failTimestamp(map, REQUESTED_AT)
        WearDecodeResult.Success(build(id, command, timestamp))
    }
    private fun <T> decodeActionWithTrip(bytes: ByteArray, path: String, build: (String, String, String, Long) -> T): WearDecodeResult<T> = decode(bytes, path) { map, id ->
        val command = map.getString(COMMAND_ID)?.takeIf { it.isNotBlank() } ?: return@decode WearDecodeResult.Failure(WearProtocolError.RequiredFieldMissing(COMMAND_ID))
        val trip = map.getString(REMOTE_TRIP_ID)?.takeIf { it.isNotBlank() } ?: return@decode WearDecodeResult.Failure(WearProtocolError.RequiredFieldMissing(REMOTE_TRIP_ID))
        val timestamp = timestamp(map, REQUESTED_AT) ?: return@decode failTimestamp(map, REQUESTED_AT); WearDecodeResult.Success(build(id, command, trip, timestamp))
    }
    private fun <T> decodeActionWithTripMap(map: DataMap, path: String, build: (String, String, String, Long) -> T): WearDecodeResult<T> = decodeMap(map, path) { _, id ->
        val command = map.getString(COMMAND_ID)?.takeIf { it.isNotBlank() } ?: return@decodeMap WearDecodeResult.Failure(WearProtocolError.RequiredFieldMissing(COMMAND_ID))
        val trip = map.getString(REMOTE_TRIP_ID)?.takeIf { it.isNotBlank() } ?: return@decodeMap WearDecodeResult.Failure(WearProtocolError.RequiredFieldMissing(REMOTE_TRIP_ID))
        val timestamp = timestamp(map, REQUESTED_AT) ?: return@decodeMap failTimestamp(map, REQUESTED_AT)
        WearDecodeResult.Success(build(id, command, trip, timestamp))
    }
    private fun timestamp(map: DataMap, key: String): Long? = map.getLong(key).takeIf { it > 0L }
    private fun failTimestamp(map: DataMap, key: String): WearDecodeResult.Failure =
        WearDecodeResult.Failure(WearProtocolError.InvalidTimestamp(key, map.getLong(key)))
}
