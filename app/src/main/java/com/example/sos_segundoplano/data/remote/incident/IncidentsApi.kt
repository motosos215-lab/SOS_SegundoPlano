package com.example.sos_segundoplano.data.remote.incident

import com.example.sos_segundoplano.data.remote.auth.ApiEnvelopeDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

interface IncidentsApi {
    @GET("api/v1/incidents")
    suspend fun listIncidents(@Header("Authorization") authorization: String): Response<ApiEnvelopeDto<IncidentHistoryPageDto>>
    @POST("api/v1/incidents")
    suspend fun createIncident(
        @Header("Authorization") authorization: String,
        @Body request: CreateIncidentRequestDto
    ): Response<ApiEnvelopeDto<CreateIncidentDataDto>>
}
