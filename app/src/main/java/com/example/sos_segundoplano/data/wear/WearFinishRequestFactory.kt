package com.example.sos_segundoplano.data.wear

import com.example.sos_segundoplano.data.remote.trip.FinishTripRequestDto
import com.example.sos_segundoplano.data.remote.trip.toTripLocationDto
import com.example.sos_segundoplano.domain.signals.LocationSample
import com.example.sos_segundoplano.domain.signals.SignalAvailability

internal fun buildWearFinishRequest(
    finishedAtUtc: String,
    availability: SignalAvailability,
    location: LocationSample?
): FinishTripRequestDto = FinishTripRequestDto(
    clientFinishedAtUtc = finishedAtUtc,
    endLocation = location?.takeIf { availability == SignalAvailability.Available }?.toTripLocationDto()
)
