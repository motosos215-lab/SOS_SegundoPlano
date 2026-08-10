package com.example.sos_segundoplano.data.remote.trip

import com.squareup.moshi.JsonClass

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
