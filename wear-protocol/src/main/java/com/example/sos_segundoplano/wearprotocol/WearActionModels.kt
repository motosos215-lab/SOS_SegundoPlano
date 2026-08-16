package com.example.sos_segundoplano.wearprotocol

/** Closed, sanitized outcomes for mutations requested by the companion watch. */
enum class PhoneActionResult(val wireValue: String) {
    OK("ok"), REJECTED("rejected"), RETRYABLE_ERROR("retryable_error"),
    PHONE_ACTION_REQUIRED("phone_action_required"), NOT_AUTHENTICATED("not_authenticated"),
    WRONG_ROLE("wrong_role"), NO_ACTIVE_TRIP("no_active_trip"), TRIP_MISMATCH("trip_mismatch"),
    UNAVAILABLE("unavailable"), INVALID_REQUEST("invalid_request");

    companion object { fun fromWire(value: String?) = entries.firstOrNull { it.wireValue == value } }
}

data class TripStateRequest(
    val requestId: String,
    val requestedAtEpochMs: Long,
    val protocolVersion: Int = WearProtocol.PROTOCOL_VERSION,
    val schemaVersion: Int = WearProtocol.SCHEMA_VERSION
)

data class TripStateResponse(
    val requestId: String,
    val active: Boolean,
    val remoteTripId: String?,
    val startedAtEpochMs: Long?,
    val updatedAtEpochMs: Long,
    val result: PhoneActionResult = PhoneActionResult.OK,
    val sanitizedCode: String? = null,
    val protocolVersion: Int = WearProtocol.PROTOCOL_VERSION,
    val schemaVersion: Int = WearProtocol.SCHEMA_VERSION
)

sealed interface PhoneActionRequest {
    val requestId: String
    val commandId: String
    val requestedAtEpochMs: Long
    val protocolVersion: Int
    val schemaVersion: Int
}

data class StartTripActionRequest(
    override val requestId: String,
    override val commandId: String,
    override val requestedAtEpochMs: Long,
    override val protocolVersion: Int = WearProtocol.PROTOCOL_VERSION,
    override val schemaVersion: Int = WearProtocol.SCHEMA_VERSION
) : PhoneActionRequest

data class FinishTripActionRequest(
    override val requestId: String,
    override val commandId: String,
    val remoteTripId: String,
    override val requestedAtEpochMs: Long,
    override val protocolVersion: Int = WearProtocol.PROTOCOL_VERSION,
    override val schemaVersion: Int = WearProtocol.SCHEMA_VERSION
) : PhoneActionRequest

data class ManualSosActionRequest(
    override val requestId: String,
    override val commandId: String,
    val remoteTripId: String,
    override val requestedAtEpochMs: Long,
    override val protocolVersion: Int = WearProtocol.PROTOCOL_VERSION,
    override val schemaVersion: Int = WearProtocol.SCHEMA_VERSION
) : PhoneActionRequest

data class PhoneActionResponse(
    val requestId: String,
    val result: PhoneActionResult,
    val sanitizedCode: String? = null,
    val remoteTripId: String? = null,
    val respondedAtEpochMs: Long,
    val protocolVersion: Int = WearProtocol.PROTOCOL_VERSION,
    val schemaVersion: Int = WearProtocol.SCHEMA_VERSION
)
