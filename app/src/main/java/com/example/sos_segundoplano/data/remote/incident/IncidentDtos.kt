package com.example.sos_segundoplano.data.remote.incident

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = false)
data class CreateIncidentRequestDto(
    val tripId: String,
    val clientIncidentId: String,
    val source: String,
    val cause: String,
    val riskLevel: String,
    val score: Int?,
    val confidence: Double,
    val gpsQuality: String,
    val ruleSetVersion: String,
    val validationPolicyVersion: String,
    val occurredAtUtc: String
)

@JsonClass(generateAdapter = false)
data class CreateIncidentDataDto(
    val incidentId: String? = null,
    val incident: CreatedIncidentDto? = null
)

@JsonClass(generateAdapter = false)
data class CreatedIncidentDto(
    val id: String? = null,
    val tripId: String? = null,
    val vehicleId: String? = null,
    val mobileDeviceId: String? = null,
    val smartwatchDeviceId: String? = null,
    val source: String? = null,
    val cause: String? = null,
    val riskLevel: String? = null,
    val status: String? = null,
    val score: Int? = null,
    val confidence: Double? = null,
    val occurredAtUtc: String? = null,
    val createdAtUtc: String? = null,
    val updatedAtUtc: String? = null,
    val cancelledAtUtc: String? = null,
    val closedAtUtc: String? = null,
    val closureReason: String? = null,
    val closureNotes: String? = null
)
