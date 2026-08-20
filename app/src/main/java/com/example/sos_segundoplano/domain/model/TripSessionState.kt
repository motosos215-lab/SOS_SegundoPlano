package com.example.sos_segundoplano.domain.model

sealed interface TripSessionState {
    data object Idle : TripSessionState
    data class Active(val tripSessionKey: String) : TripSessionState
}
