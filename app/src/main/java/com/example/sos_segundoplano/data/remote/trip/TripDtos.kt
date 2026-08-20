package com.example.sos_segundoplano.data.remote.trip

import com.squareup.moshi.JsonClass
import com.example.sos_segundoplano.domain.signals.LocationSample
import java.time.Instant

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

@JsonClass(generateAdapter = false)
data class TripHistoryDto(
    val id: String? = null,
    val status: String? = null,
    val startedAtUtc: String? = null,
    val finishedAtUtc: String? = null,
    val clientStartedAtUtc: String? = null,
    val clientFinishedAtUtc: String? = null,
    val startLocation: TripLocationDto? = null,
    val endLocation: TripLocationDto? = null
)

fun LocationSample.toTripLocationDto(): TripLocationDto = TripLocationDto(
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = accuracyMeters.toDouble(),
    provider = provider,
    recordedAtUtc = Instant.ofEpochMilli(timestampMillis).toString()
)

@JsonClass(generateAdapter = false)
data class TripHistoryPageDto(
    val trips: List<TripHistoryDto>? = null,
    val pageNumber: Int? = null,
    val pageSize: Int? = null,
    val totalCount: Int? = null
)

/**
 * A real GPS point captured by the Rider during a trip.
 * clientRoutePointId and sequence are stable across retries so the backend can apply idempotency.
 */
@JsonClass(generateAdapter = false)
data class TripRoutePointUploadDto(
    val clientRoutePointId: String,
    val sequence: Long,
    val recordedAtUtc: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val speedMetersPerSecond: Double? = null,
    val bearingDegrees: Double? = null,
    val appVersion: String? = null,
    val deviceId: String? = null
)

/**
 * The backend contract describes a batch endpoint whose items use TripRoutePointUploadDto.
 * Keeping the items under `points` makes the wire contract explicit and easy to evolve.
 */
@JsonClass(generateAdapter = false)
data class TripRoutePointsBatchRequestDto(
    val points: List<TripRoutePointUploadDto>
)

@JsonClass(generateAdapter = false)
data class TripRoutePointsBatchDataDto(
    val acceptedCount: Int? = null,
    val insertedCount: Int? = null,
    val duplicateCount: Int? = null
)

@JsonClass(generateAdapter = false)
data class TripRoutePointDto(
    val clientRoutePointId: String? = null,
    val sequence: Long? = null,
    val recordedAtUtc: String? = null,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double? = null,
    val speedMetersPerSecond: Double? = null,
    val bearingDegrees: Double? = null,
    val appVersion: String? = null,
    val deviceId: String? = null
)

/**
 * Tolerates the two most common envelope property names while the mobile app and API are
 * deployed independently. No synthetic geometry is generated when neither collection exists.
 */
@JsonClass(generateAdapter = false)
data class TripRouteDataDto(
    val tripId: String? = null,
    val points: List<TripRoutePointDto>? = null,
    val routePoints: List<TripRoutePointDto>? = null
) {
    fun resolvedPoints(): List<TripRoutePointDto> = (routePoints ?: points).orEmpty()
}
