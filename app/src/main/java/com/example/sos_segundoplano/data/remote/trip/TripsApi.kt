package com.example.sos_segundoplano.data.remote.trip

import com.example.sos_segundoplano.data.remote.auth.ApiEnvelopeDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header

interface TripsApi {
    @GET("api/v1/trips/active")
    suspend fun activeTrip(@Header("Authorization") authorization: String): Response<ApiEnvelopeDto<ActiveTripDataDto>>
}
