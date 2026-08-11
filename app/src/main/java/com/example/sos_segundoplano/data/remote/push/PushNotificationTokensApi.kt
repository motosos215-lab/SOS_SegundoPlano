package com.example.sos_segundoplano.data.remote.push

import com.example.sos_segundoplano.data.remote.auth.ApiEnvelopeDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface PushNotificationTokensApi {
    @POST("api/v1/push-notification-tokens")
    suspend fun register(
        @Header("Authorization") authorization: String,
        @Body request: RegisterPushTokenRequestDto
    ): Response<ApiEnvelopeDto<RegisterPushTokenDataDto>>

    @GET("api/v1/push-notification-tokens")
    suspend fun list(
        @Header("Authorization") authorization: String,
        @Query("platform") platform: String = "Android",
        @Query("channel") channel: String = "Fcm",
        @Query("status") status: String = "Active",
        @Query("pageNumber") pageNumber: Int = 1,
        @Query("pageSize") pageSize: Int = 20
    ): Response<ApiEnvelopeDto<PushTokenListDataDto>>

    @GET("api/v1/push-notification-tokens/status")
    suspend fun status(
        @Header("Authorization") authorization: String
    ): Response<ApiEnvelopeDto<PushTokenStatusDataDto>>

    @POST("api/v1/push-notification-tokens/{id}/revoke")
    suspend fun revoke(
        @Header("Authorization") authorization: String,
        @Path("id") registrationId: String
    ): Response<ApiEnvelopeDto<PushNotificationTokenDto>>
}

data class RegisterPushTokenRequestDto(
    val platform: String,
    val channel: String,
    val token: String,
    val metadata: PushTokenMetadataDto
) {
    override fun toString(): String =
        "RegisterPushTokenRequestDto(platform=$platform, channel=$channel, token=[REDACTED], metadata=$metadata)"
}

data class PushTokenMetadataDto(
    val appVersion: String,
    val osVersion: String
)

data class RegisterPushTokenDataDto(
    val pushNotificationToken: PushNotificationTokenDto
)

data class PushNotificationTokenDto(
    val id: String,
    val platform: String,
    val channel: String,
    val deviceId: String?,
    val tokenPreview: String,
    val status: String,
    val registeredAtUtc: String,
    val lastSeenAtUtc: String,
    val revokedAtUtc: String?
)

data class PushTokenListDataDto(
    val pushNotificationTokens: List<PushNotificationTokenDto>,
    val pageNumber: Int,
    val pageSize: Int,
    val totalCount: Int
)

data class PushTokenStatusDataDto(
    val activeTokenCount: Int,
    val revokedTokenCount: Int,
    val hasActiveAndroidFcm: Boolean,
    val hasActiveIosApns: Boolean,
    val hasActiveWebPush: Boolean,
    val hasActiveWebFcm: Boolean,
    val lastRegisteredAtUtc: String?
)
