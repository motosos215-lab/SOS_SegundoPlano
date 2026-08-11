package com.example.sos_segundoplano.data.remote.trip

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = false)
data class ActiveTripDataDto(
    val trip: RemoteTripDto? = null,
    val id: String? = null,
    val status: String? = null
)

@JsonClass(generateAdapter = false)
data class RemoteTripDto(
    val id: String? = null,
    val status: String? = null
)

@JsonClass(generateAdapter = false)
data class TripLocationDto(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double? = null,
    val provider: String? = null,
    val recordedAtUtc: String? = null
)

@JsonClass(generateAdapter = false)
data class StartTripRequestDto(
    val vehicleId: String,
    val mobileDeviceId: String,
    val smartwatchDeviceId: String? = null,
    val clientStartedAtUtc: String? = null,
    val startLocation: TripLocationDto? = null,
    val batteryLevel: Int? = null,
    val appVersion: String? = null
)

@JsonClass(generateAdapter = false)
data class FinishTripRequestDto(
    val clientFinishedAtUtc: String? = null,
    val endLocation: TripLocationDto? = null,
    val batteryLevel: Int? = null,
    val notes: String? = null
)

@JsonClass(generateAdapter = false)
data class TripMutationDataDto(
    val trip: RemoteTripDto? = null
)
