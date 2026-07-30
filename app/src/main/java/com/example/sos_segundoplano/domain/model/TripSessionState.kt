package com.example.sos_segundoplano.domain.model

sealed interface TripSessionState {
    data object Idle : TripSessionState
    data object Active : TripSessionState
}
