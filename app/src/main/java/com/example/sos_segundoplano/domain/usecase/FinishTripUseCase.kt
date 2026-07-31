package com.example.sos_segundoplano.domain.usecase

import com.example.sos_segundoplano.domain.model.TripSessionState

class FinishTripUseCase {
    operator fun invoke(currentState: TripSessionState): TripSessionState = when (currentState) {
        TripSessionState.Active -> TripSessionState.Idle
        TripSessionState.Idle -> TripSessionState.Idle
    }
}
