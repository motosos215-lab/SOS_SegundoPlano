package com.example.sos_segundoplano.data.remote.incident

import com.example.sos_segundoplano.data.remote.auth.ApiEnvelopeDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface MobileSosAlertsApi {
    @POST("api/v1/mobile/sos-alerts")
    suspend fun createManualSosAlert(
        @Header("Authorization") authorization: String,
        @Body request: ManualSosAlertRequestDto
    ): Response<ApiEnvelopeDto<ManualSosAlertDataDto>>
}
