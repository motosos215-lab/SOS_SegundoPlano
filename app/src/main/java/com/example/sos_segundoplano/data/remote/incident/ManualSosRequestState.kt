package com.example.sos_segundoplano.data.remote.incident

import com.example.sos_segundoplano.domain.sos.MobileSosPriority
import com.example.sos_segundoplano.domain.sos.MobileSosSeverity

/**
 * Manual SOS remains one-tap by default. Classification is optional and never blocks sending.
 * Unknown + High means: the Rider requested help, but MotoSOS is not inventing a measured risk.
 */
data class ManualSosSubmissionOptions(
    val severity: MobileSosSeverity = MobileSosSeverity.Unknown,
    val priority: MobileSosPriority = MobileSosPriority.High
)

sealed interface ManualSosRequestState {
    data object Idle : ManualSosRequestState
    data object Preparing : ManualSosRequestState
    data object Retrying : ManualSosRequestState
    data object WaitingForLocation : ManualSosRequestState
    data object Sending : ManualSosRequestState
    data object Sent : ManualSosRequestState
    /** The SOS identity is durably stored and a background worker will retry automatically. */
    data object SavedOffline : ManualSosRequestState
    data object LocationUnavailable : ManualSosRequestState
    data object RetryableFailure : ManualSosRequestState
}

fun interface ManualSosProgressReporter {
    fun report(state: ManualSosRequestState)
}
