package com.example.sos_segundoplano.wear

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal enum class WearValidationUiOperation {
    ConfirmSafe,
    RequestHelp
}

internal sealed interface WearValidationUiActionState {
    data object Idle : WearValidationUiActionState
    data class InFlight(val operation: WearValidationUiOperation) : WearValidationUiActionState
    data class Error(
        val operation: WearValidationUiOperation,
        val message: String,
        val retryable: Boolean
    ) : WearValidationUiActionState
    data class Sent(val operation: WearValidationUiOperation) : WearValidationUiActionState
}

internal class WearValidationUiController(
    private val actions: WearValidationActionClient,
    private val validationStatusProvider: () -> WearDataLayerProtocol.ValidationStatus? = {
        WearValidationStateStore.state.value
    }
) {
    private val mutableActionState = MutableStateFlow<WearValidationUiActionState>(WearValidationUiActionState.Idle)
    val actionState: StateFlow<WearValidationUiActionState> = mutableActionState

    private var assessmentKey: AssessmentKey? = null

    fun onValidationStatusChanged(status: WearDataLayerProtocol.ValidationStatus?) {
        val newKey = status.activeAssessmentKey()
        if (assessmentKey != newKey) {
            assessmentKey = newKey
            mutableActionState.value = WearValidationUiActionState.Idle
        }
    }

    suspend fun confirmSafe() = submit(WearValidationUiOperation.ConfirmSafe)

    suspend fun requestHelp() = submit(WearValidationUiOperation.RequestHelp)

    suspend fun retry() {
        onValidationStatusChanged(validationStatusProvider())
        when (val state = mutableActionState.value) {
            is WearValidationUiActionState.Error -> submit(state.operation)
            else -> Unit
        }
    }

    private suspend fun submit(operation: WearValidationUiOperation) {
        if (mutableActionState.value is WearValidationUiActionState.InFlight) return
        onValidationStatusChanged(validationStatusProvider())
        val operationAssessmentKey = assessmentKey
            ?: run {
                mutableActionState.value = WearValidationUiActionState.Idle
                return
            }
        mutableActionState.value = WearValidationUiActionState.InFlight(operation)
        val result = when (operation) {
            WearValidationUiOperation.ConfirmSafe -> actions.confirmSafe()
            WearValidationUiOperation.RequestHelp -> actions.requestHelp()
        }
        onValidationStatusChanged(validationStatusProvider())
        if (assessmentKey == operationAssessmentKey) {
            mutableActionState.value = if (result == WearValidationActionResult.NoActiveCountdown) {
                WearValidationUiActionState.Idle
            } else {
                result.toUiState(operation)
            }
        }
    }

    private fun WearValidationActionResult.toUiState(
        operation: WearValidationUiOperation
    ): WearValidationUiActionState = when (this) {
        WearValidationActionResult.Sent -> WearValidationUiActionState.Sent(operation)
        WearValidationActionResult.NoActiveCountdown -> WearValidationUiActionState.Error(
            operation,
            "La validación ya no está activa.",
            retryable = false
        )
        WearValidationActionResult.CompanionUnavailable -> WearValidationUiActionState.Error(
            operation,
            "Teléfono no disponible.",
            retryable = true
        )
        WearValidationActionResult.Timeout -> WearValidationUiActionState.Error(
            operation,
            "No se recibió respuesta. Inténtalo de nuevo.",
            retryable = true
        )
        WearValidationActionResult.TransportFailure -> WearValidationUiActionState.Error(
            operation,
            "No se pudo enviar la respuesta. Inténtalo de nuevo.",
            retryable = true
        )
    }

    private data class AssessmentKey(val sessionId: Long?, val assessmentId: Long?)

    private fun WearDataLayerProtocol.ValidationStatus?.activeAssessmentKey(): AssessmentKey? =
        this?.takeIf {
            it.isCountdownActive && it.sessionId != null && it.assessmentId != null
        }?.let { AssessmentKey(it.sessionId, it.assessmentId) }
}
