package com.example.sos_segundoplano.data.remote.auth

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

interface AuthApi {
    @POST("api/v1/auth/login")
    suspend fun login(@Body request: LoginRequestDto): Response<ApiEnvelopeDto<LoginDataDto>>

    @POST("api/v1/auth/login-with-code")
    suspend fun loginWithCode(@Body request: LoginWithCodeRequestDto): Response<ApiEnvelopeDto<LoginDataDto>>

    @POST("api/v1/auth/sessions/takeover")
    suspend fun takeover(@Body request: SessionTakeoverRequestDto): Response<ApiEnvelopeDto<LoginDataDto>>

    @POST("api/v1/auth/refresh")
    suspend fun refresh(@Body request: RefreshTokenRequestDto): Response<ApiEnvelopeDto<RefreshDataDto>>

    @POST("api/v1/auth/logout")
    suspend fun logout(
        @Header("Authorization") authorization: String,
        @Body request: LogoutRequestDto
    ): Response<Unit>

    @GET("api/v1/users/me")
    suspend fun currentUser(
        @Header("Authorization") authorization: String
    ): Response<ApiEnvelopeDto<CurrentUserDataDto>>
}
