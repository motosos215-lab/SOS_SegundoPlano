package com.example.sos_segundoplano.data.remote.incident

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = false)
data class CreateIncidentRequestDto(
    val tripId: String,
    val clientIncidentId: String,
    val source: String,
    val cause: String,
    val riskLevel: String,
    val score: Int,
    val confidence: Double,
    val gpsQuality: String,
    val ruleSetVersion: String,
    val validationPolicyVersion: String,
    val occurredAtUtc: String
)

@JsonClass(generateAdapter = false)
data class CreateIncidentDataDto(
    val incidentId: String?
)
