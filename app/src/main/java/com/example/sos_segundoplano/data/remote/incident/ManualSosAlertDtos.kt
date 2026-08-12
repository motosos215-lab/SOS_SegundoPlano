package com.example.sos_segundoplano.data.remote.incident

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = false)
data class ManualSosAlertRequestDto(
    val tripId: String,
    val clientIncidentId: String,
    val clientAlertRequestId: String,
    val incidentType: String,
    val severity: String,
    val detectedAtUtc: String,
    val latitude: Double,
    val longitude: Double,
    val priority: String,
    val reason: String,
    val notes: String? = null
)

@JsonClass(generateAdapter = false)
data class ManualSosAlertDataDto(
    val incident: ManualSosCreatedIncidentDto? = null,
    val alertDispatch: ManualSosAlertDispatchDto? = null,
    val notificationAttempts: List<ManualSosNotificationAttemptDto>? = null,
    val summary: ManualSosAlertSummaryDto? = null
)

@JsonClass(generateAdapter = false)
data class ManualSosCreatedIncidentDto(
    val id: String? = null,
    val tripId: String? = null,
    val status: String? = null,
    val incidentType: String? = null,
    val severity: String? = null
)

@JsonClass(generateAdapter = false)
data class ManualSosAlertDispatchDto(
    val id: String? = null,
    val incidentId: String? = null,
    val status: String? = null,
    val contactsCount: Int? = null
)

@JsonClass(generateAdapter = false)
data class ManualSosNotificationAttemptDto(
    val id: String? = null,
    val alertDispatchId: String? = null,
    val incidentId: String? = null,
    val tripId: String? = null,
    val emergencyContactId: String? = null,
    val contactFullName: String? = null,
    val channel: String? = null,
    val status: String? = null,
    val provider: String? = null
)

@JsonClass(generateAdapter = false)
data class ManualSosAlertSummaryDto(
    val pushPrepared: Int? = null,
    val smsPrepared: Int? = null,
    val emailPrepared: Int? = null,
    val totalPrepared: Int? = null
)
