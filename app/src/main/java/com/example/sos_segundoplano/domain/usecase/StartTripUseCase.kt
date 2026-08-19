package com.example.sos_segundoplano.domain.usecase

import com.example.sos_segundoplano.domain.model.TripSessionState

class StartTripUseCase {
    operator fun invoke(currentState: TripSessionState): TripSessionState = when (currentState) {
        TripSessionState.Idle -> TripSessionState.Active(java.util.UUID.randomUUID().toString())
        is TripSessionState.Active -> currentState
    }
}
