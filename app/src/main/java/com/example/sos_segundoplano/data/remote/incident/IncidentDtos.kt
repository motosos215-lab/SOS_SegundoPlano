package com.example.sos_segundoplano.data.remote.incident

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = false)
data class CreateIncidentRequestDto(
    val tripId: String,
    val clientIncidentId: String,
    val source: String,
    val cause: String,
    val riskLevel: String,
    val occurredAtUtc: String,
    val location: IncidentLocationDto? = null,
    val evidenceSummary: IncidentEvidenceSummaryDto? = null
)

@JsonClass(generateAdapter = false)
data class IncidentLocationDto(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double? = null,
    val speedKmh: Double? = null,
    val provider: String? = null,
    val recordedAtUtc: String? = null
)

@JsonClass(generateAdapter = false)
data class IncidentEvidenceSummaryDto(
    val assessmentId: Long? = null,
    val windowId: Long? = null,
    val triggeredRules: List<String>? = null,
    val hasSmartwatchData: Boolean? = null,
    val hasLocation: Boolean? = null,
    val phoneBatteryLevel: Int? = null,
    val watchBatteryLevel: Int? = null,
    val appVersion: String? = null
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

@JsonClass(generateAdapter = false)
data class IncidentHistoryDto(
    val id: String? = null,
    val tripId: String? = null,
    val cause: String? = null,
    val riskLevel: String? = null,
    val status: String? = null,
    val occurredAtUtc: String? = null,
    val createdAtUtc: String? = null
)

@JsonClass(generateAdapter = false)
data class IncidentHistoryPageDto(
    val incidents: List<IncidentHistoryDto>? = null,
    val pageNumber: Int? = null,
    val pageSize: Int? = null,
    val totalCount: Int? = null
)
