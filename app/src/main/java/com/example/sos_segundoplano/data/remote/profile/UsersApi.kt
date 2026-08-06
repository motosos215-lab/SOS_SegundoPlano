package com.example.sos_segundoplano.data.remote.profile

import com.example.sos_segundoplano.data.remote.auth.ApiEnvelopeDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header

interface UsersApi {
    @GET("api/v1/users/me")
    suspend fun me(@Header("Authorization") authorization: String): Response<ApiEnvelopeDto<ProfileDataDto>>
}
