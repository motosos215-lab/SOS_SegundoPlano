package com.example.sos_segundoplano.data.remote.incident

import com.example.sos_segundoplano.data.remote.auth.ApiEnvelopeDto
import com.example.sos_segundoplano.domain.auth.AuthResult
import com.example.sos_segundoplano.domain.repository.AuthRepository
import com.example.sos_segundoplano.domain.signals.LocationSample
import com.squareup.moshi.JsonClass
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonEncodingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import java.util.UUID

@JsonClass(generateAdapter = false)
data class EmergencyLocationSnapshotRequestDto(
    val incidentId: String,
    val clientLocationUpdateId: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double? = null,
    val altitudeMeters: Double? = null,
    val speedMetersPerSecond: Double? = null,
    val headingDegrees: Double? = null,
    val batteryPercentage: Int? = null,
    val source: String = SOURCE,
    val recordedAtUtc: String
) {
    companion object { const val SOURCE = "MobileApp" }
}

interface EmergencyLocationSharingApi {
    @POST("api/v1/mobile/location-sharing/snapshot")
    suspend fun publishSnapshot(
        @Header("Authorization") authorization: String,
        @Body request: EmergencyLocationSnapshotRequestDto
    ): Response<ApiEnvelopeDto<Any>>
}

sealed interface EmergencyLocationPublicationResult {
    data object Published : EmergencyLocationPublicationResult
    data class Failed(val sanitizedMessage: String) : EmergencyLocationPublicationResult
}

fun interface EmergencyLocationPublisher {
    suspend fun publish(snapshot: EmergencyLocationSnapshotRequestDto): EmergencyLocationPublicationResult
}

object NoOpEmergencyLocationPublisher : EmergencyLocationPublisher {
    override suspend fun publish(snapshot: EmergencyLocationSnapshotRequestDto): EmergencyLocationPublicationResult =
        EmergencyLocationPublicationResult.Failed("location_publisher_unavailable")
}

suspend fun EmergencyLocationPublisher.publishSafely(
    snapshot: EmergencyLocationSnapshotRequestDto,
    timeoutMillis: Long = SECONDARY_LOCATION_PUBLISH_TIMEOUT_MILLIS
) {
    if (timeoutMillis <= 0L) return
    try {
        // The SOS itself has already been created at this point. Location sharing is secondary and
        // must never keep Manual/Automatic SOS stuck in "Sending" if this endpoint is slow.
        withTimeoutOrNull(timeoutMillis) { publish(snapshot) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        // Best effort only. The primary emergency remains successful.
    }
}

private const val SECONDARY_LOCATION_PUBLISH_TIMEOUT_MILLIS = 2_500L

class AuthenticatedEmergencyLocationPublisher(
    private val authRepository: AuthRepository,
    private val api: EmergencyLocationSharingApi
) : EmergencyLocationPublisher {
    override suspend fun publish(snapshot: EmergencyLocationSnapshotRequestDto): EmergencyLocationPublicationResult {
        val token = when (val auth = authRepository.ensureValidAccessToken()) {
            is AuthResult.Success -> auth.value.reveal()
            else -> return EmergencyLocationPublicationResult.Failed("access_token_unavailable")
        }
        val first = submit("Bearer $token", snapshot)
        if (first !is EmergencyLocationPublicationResult.Failed || first.sanitizedMessage != "http_401") return first
        if (authRepository.refreshSession() !is AuthResult.Success) return first
        val refreshedToken = when (val auth = authRepository.ensureValidAccessToken()) {
            is AuthResult.Success -> auth.value.reveal()
            else -> return EmergencyLocationPublicationResult.Failed("access_token_unavailable")
        }
        return submit("Bearer $refreshedToken", snapshot)
    }

    private suspend fun submit(
        authorization: String,
        snapshot: EmergencyLocationSnapshotRequestDto
    ): EmergencyLocationPublicationResult {
        return try {
            val response = api.publishSnapshot(authorization, snapshot)
            if (!response.isSuccessful) return EmergencyLocationPublicationResult.Failed("http_${response.code()}")
            val envelope = response.body() ?: return EmergencyLocationPublicationResult.Failed("response_body_missing")
            if (envelope.success) EmergencyLocationPublicationResult.Published
            else EmergencyLocationPublicationResult.Failed(envelope.error?.code?.sanitize() ?: "request_rejected")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: JsonDataException) {
            EmergencyLocationPublicationResult.Failed("response_json_invalid")
        } catch (_: JsonEncodingException) {
            EmergencyLocationPublicationResult.Failed("response_json_invalid")
        } catch (_: EOFException) {
            EmergencyLocationPublicationResult.Failed("response_body_invalid")
        } catch (_: SocketTimeoutException) {
            EmergencyLocationPublicationResult.Failed("network_timeout")
        } catch (_: UnknownHostException) {
            EmergencyLocationPublicationResult.Failed("network_unavailable")
        } catch (_: ConnectException) {
            EmergencyLocationPublicationResult.Failed("network_unavailable")
        } catch (_: NoRouteToHostException) {
            EmergencyLocationPublicationResult.Failed("network_unavailable")
        } catch (_: IOException) {
            EmergencyLocationPublicationResult.Failed("network_unavailable")
        } catch (_: IllegalArgumentException) {
            EmergencyLocationPublicationResult.Failed("response_invalid")
        }
    }

    private fun String.sanitize(): String = replace(Regex("[\\r\\n\\t]"), " ").take(160)
}

fun LocationSample.toEmergencyLocationSnapshot(incidentId: String): EmergencyLocationSnapshotRequestDto? {
    if (incidentId.isBlank() || !latitude.isFinite() || !longitude.isFinite()) return null
    if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0 || (latitude == 0.0 && longitude == 0.0)) return null
    return EmergencyLocationSnapshotRequestDto(
        incidentId = incidentId,
        clientLocationUpdateId = UUID.randomUUID().toString(),
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracyMeters.takeIf { it.isFinite() && it >= 0f }?.toDouble(),
        altitudeMeters = altitudeMeters?.takeIf { it.isFinite() },
        speedMetersPerSecond = speedMetersPerSecond?.takeIf { it.isFinite() && it >= 0f }?.toDouble(),
        headingDegrees = bearingDegrees?.takeIf { it.isFinite() }?.toDouble(),
        recordedAtUtc = Instant.ofEpochMilli(timestampMillis).toString()
    )
}
