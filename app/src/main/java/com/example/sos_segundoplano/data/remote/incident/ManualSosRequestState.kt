package com.example.sos_segundoplano.data.remote.incident

sealed interface ManualSosRequestState {
    data object Idle : ManualSosRequestState
    data object Preparing : ManualSosRequestState
    data object Retrying : ManualSosRequestState
    data object WaitingForLocation : ManualSosRequestState
    data object Sending : ManualSosRequestState
    data object Sent : ManualSosRequestState
    data object LocationUnavailable : ManualSosRequestState
    data object RetryableFailure : ManualSosRequestState
}

fun interface ManualSosProgressReporter {
    fun report(state: ManualSosRequestState)
}
