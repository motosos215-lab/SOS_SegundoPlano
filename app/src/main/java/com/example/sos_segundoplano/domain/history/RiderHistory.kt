package com.example.sos_segundoplano.domain.history

data class RiderTripHistoryItem(
    val status: String?,
    val startedAtUtc: String?,
    val finishedAtUtc: String?,
    val startLocation: RiderTripHistoryLocation? = null,
    val endLocation: RiderTripHistoryLocation? = null
)

data class RiderTripHistoryLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double? = null,
    val provider: String? = null,
    val recordedAtUtc: String? = null
)

data class RiderIncidentHistoryItem(
    val cause: String?,
    val riskLevel: String?,
    val status: String?,
    val occurredAtUtc: String?
)

sealed interface RiderHistoryResult<out T> {
    data class Success<T>(val value: T) : RiderHistoryResult<T>
    data class Failure(val message: String?) : RiderHistoryResult<Nothing>
}

interface RiderHistoryRepository {
    suspend fun trips(): RiderHistoryResult<List<RiderTripHistoryItem>>
    suspend fun incidents(): RiderHistoryResult<List<RiderIncidentHistoryItem>>
}
