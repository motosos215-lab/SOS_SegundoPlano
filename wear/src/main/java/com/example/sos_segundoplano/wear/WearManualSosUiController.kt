package com.example.sos_segundoplano.wear

import com.example.sos_segundoplano.wearprotocol.PhoneActionResponse
import com.example.sos_segundoplano.wearprotocol.PhoneActionResult
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal sealed interface WearManualSosUiState {
    data object Idle : WearManualSosUiState
    data class Holding(val progress: Float) : WearManualSosUiState
    data object InFlight : WearManualSosUiState
    data object Success : WearManualSosUiState
    data class Error(val message: String, val retryable: Boolean) : WearManualSosUiState
}

/** Wear-only presentation state. The Phone remains the authority for SOS creation and trip state. */
internal class WearManualSosUiController(
    private val actions: MobileCompanionClient,
    private val tripStateStore: WearTripStateStore,
    private val commandIdProvider: () -> String = { UUID.randomUUID().toString() }
) {
    private val mutableState = MutableStateFlow<WearManualSosUiState>(WearManualSosUiState.Idle)
    val state: StateFlow<WearManualSosUiState> = mutableState.asStateFlow()
    private var pendingCommandId: String? = null

    fun startHold() {
        if (mutableState.value is WearManualSosUiState.Idle) {
            mutableState.value = WearManualSosUiState.Holding(0f)
        }
    }

    fun updateHoldProgress(progress: Float) {
        if (mutableState.value is WearManualSosUiState.Holding) {
            mutableState.value = WearManualSosUiState.Holding(progress.coerceIn(0f, 1f))
        }
    }

    fun cancelHold() {
        if (mutableState.value is WearManualSosUiState.Holding) {
            mutableState.value = WearManualSosUiState.Idle
        }
    }

    suspend fun confirmHeld() {
        if (mutableState.value !is WearManualSosUiState.Holding) return
        sendPendingAction()
    }

    suspend fun retry() {
        val error = mutableState.value as? WearManualSosUiState.Error ?: return
        if (error.retryable) sendPendingAction()
    }

    fun beginNewManualSos() {
        if (mutableState.value is WearManualSosUiState.Success) {
            pendingCommandId = null
            mutableState.value = WearManualSosUiState.Idle
        }
    }

    private suspend fun sendPendingAction() {
        if (mutableState.value is WearManualSosUiState.InFlight || mutableState.value is WearManualSosUiState.Success) return
        val trip = tripStateStore.state.value
        val remoteTripId = trip.remoteTripId?.takeIf { trip.active == true }
        if (remoteTripId == null) {
            pendingCommandId = null
            mutableState.value = WearManualSosUiState.Error("El viaje cambió en el teléfono.", retryable = false)
            return
        }
        val commandId = pendingCommandId ?: commandIdProvider().also { pendingCommandId = it }
        mutableState.value = WearManualSosUiState.InFlight
        when (val result = actions.manualSos(commandId, remoteTripId)) {
            is MobileCompanionResult.Success -> handlePhoneResponse(result.value)
            MobileCompanionResult.CompanionUnavailable -> retryableError("Teléfono no disponible.")
            MobileCompanionResult.Timeout,
            MobileCompanionResult.TransportFailure,
            MobileCompanionResult.DecodeFailure -> retryableError("No se pudo enviar la solicitud. Intenta de nuevo.")
        }
    }

    private fun handlePhoneResponse(response: PhoneActionResponse) {
        when (response.result) {
            PhoneActionResult.OK -> mutableState.value = WearManualSosUiState.Success
            PhoneActionResult.RETRYABLE_ERROR,
            PhoneActionResult.UNAVAILABLE -> retryableError("No se pudo enviar la solicitud. Intenta de nuevo.")
            PhoneActionResult.NOT_AUTHENTICATED -> terminalError("Abre MotoSOS en tu teléfono e inicia sesión.")
            PhoneActionResult.WRONG_ROLE -> terminalError("Esta función requiere una cuenta de motociclista.")
            PhoneActionResult.PHONE_ACTION_REQUIRED -> terminalError("Revisa MotoSOS en tu teléfono.")
            PhoneActionResult.NO_ACTIVE_TRIP,
            PhoneActionResult.TRIP_MISMATCH -> terminalError("El estado del viaje cambió en el teléfono.")
            PhoneActionResult.REJECTED,
            PhoneActionResult.INVALID_REQUEST -> terminalError("No fue posible enviar la solicitud.")
        }
    }

    private fun retryableError(message: String) {
        mutableState.value = WearManualSosUiState.Error(message, retryable = true)
    }

    private fun terminalError(message: String) {
        pendingCommandId = null
        mutableState.value = WearManualSosUiState.Error(message, retryable = false)
    }
}
