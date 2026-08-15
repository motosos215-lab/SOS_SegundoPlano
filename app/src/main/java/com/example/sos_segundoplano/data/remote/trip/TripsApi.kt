package com.example.sos_segundoplano.data.remote.trip

import com.example.sos_segundoplano.data.remote.auth.ApiEnvelopeDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

interface TripsApi {
    @GET("api/v1/trips")
    suspend fun listTrips(@Header("Authorization") authorization: String): Response<ApiEnvelopeDto<TripHistoryPageDto>>
    @GET("api/v1/vehicles")
    suspend fun vehicles(
        @Header("Authorization") authorization: String
    ): Response<ApiEnvelopeDto<VehiclesDataDto>>

    @GET("api/v1/devices")
    suspend fun devices(
        @Header("Authorization") authorization: String
    ): Response<ApiEnvelopeDto<DevicesDataDto>>

    @GET("api/v1/trips/active")
    suspend fun activeTrip(@Header("Authorization") authorization: String): Response<ApiEnvelopeDto<ActiveTripDataDto>>

    @POST("api/v1/trips/start")
    suspend fun startTrip(
        @Header("Authorization") authorization: String,
        @Body request: StartTripRequestDto
    ): Response<ApiEnvelopeDto<TripMutationDataDto>>

    @POST("api/v1/trips/{id}/finish")
    suspend fun finishTrip(
        @Header("Authorization") authorization: String,
        @Path("id") tripId: String,
        @Body request: FinishTripRequestDto
    ): Response<ApiEnvelopeDto<TripMutationDataDto>>
}
