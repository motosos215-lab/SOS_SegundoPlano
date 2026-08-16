package com.example.sos_segundoplano.wear

import com.example.sos_segundoplano.wearprotocol.PhoneActionResponse
import com.example.sos_segundoplano.wearprotocol.PhoneActionResult
import com.example.sos_segundoplano.wearprotocol.TripStateResponse
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal interface WearTripUiActions {
    suspend fun refreshTripState(): MobileCompanionResult<TripStateResponse>
    suspend fun startTrip(commandId: String): MobileCompanionResult<PhoneActionResponse>
    suspend fun finishTrip(commandId: String, remoteTripId: String): MobileCompanionResult<PhoneActionResponse>
}

internal class WearTripUiReconcilerActions(
    private val reconciler: WearTripStateReconciler
) : WearTripUiActions {
    override suspend fun refreshTripState() = reconciler.refreshTripState()
    override suspend fun startTrip(commandId: String) = reconciler.startTrip(commandId)
    override suspend fun finishTrip(commandId: String, remoteTripId: String) =
        reconciler.finishTrip(commandId, remoteTripId)
}

internal enum class WearTripUiOperation {
    Start,
    Finish
}

internal sealed interface WearTripUiActionState {
    data object Idle : WearTripUiActionState
    data class InFlight(val operation: WearTripUiOperation) : WearTripUiActionState
    data class Error(
        val operation: WearTripUiOperation,
        val message: String,
        val retryable: Boolean
    ) : WearTripUiActionState
}

internal class WearTripUiController(
    private val actions: WearTripUiActions,
    private val tripStateStore: WearTripStateStore,
    private val commandIdProvider: () -> String = { UUID.randomUUID().toString() }
) {
    private val mutableActionState = MutableStateFlow<WearTripUiActionState>(WearTripUiActionState.Idle)
    val actionState: StateFlow<WearTripUiActionState> = mutableActionState.asStateFlow()
    private var initialRefreshCompleted = false
    private var pendingStartCommandId: String? = null
    private var pendingFinishCommandId: String? = null

    suspend fun refreshInitial() {
        if (initialRefreshCompleted) return
        initialRefreshCompleted = true
        actions.refreshTripState()
    }

    suspend fun refresh() {
        if (mutableActionState.value is WearTripUiActionState.InFlight) return
        actions.refreshTripState()
    }

    suspend fun startTrip() {
        if (mutableActionState.value is WearTripUiActionState.InFlight) return
        val commandId = pendingStartCommandId ?: commandIdProvider().also { pendingStartCommandId = it }
        mutableActionState.value = WearTripUiActionState.InFlight(WearTripUiOperation.Start)
        handleResult(WearTripUiOperation.Start, actions.startTrip(commandId))
    }

    suspend fun finishTrip() {
        if (mutableActionState.value is WearTripUiActionState.InFlight) return
        val confirmedTrip = tripStateStore.state.value
        if (confirmedTrip.active != true || confirmedTrip.remoteTripId == null) {
            mutableActionState.value = WearTripUiActionState.Error(
                WearTripUiOperation.Finish,
                "Actualiza la conexión antes de finalizar.",
                retryable = true
            )
            return
        }
        val commandId = pendingFinishCommandId ?: commandIdProvider().also { pendingFinishCommandId = it }
        mutableActionState.value = WearTripUiActionState.InFlight(WearTripUiOperation.Finish)
        handleResult(WearTripUiOperation.Finish, actions.finishTrip(commandId, confirmedTrip.remoteTripId))
    }

    suspend fun retry() {
        when (val state = mutableActionState.value) {
            is WearTripUiActionState.Error -> if (state.retryable) {
                when (state.operation) {
                    WearTripUiOperation.Start -> startTrip()
                    WearTripUiOperation.Finish -> finishTrip()
                }
            }
            else -> Unit
        }
    }

    private suspend fun handleResult(
        operation: WearTripUiOperation,
        result: MobileCompanionResult<PhoneActionResponse>
    ) {
        when (result) {
            is MobileCompanionResult.Success -> handlePhoneResult(operation, result.value)
            MobileCompanionResult.CompanionUnavailable -> retryableError(operation, "Teléfono no disponible.")
            MobileCompanionResult.Timeout,
            MobileCompanionResult.TransportFailure,
            MobileCompanionResult.DecodeFailure -> retryableError(operation, "No se pudo completar. Intenta de nuevo.")
        }
    }

    private suspend fun handlePhoneResult(operation: WearTripUiOperation, response: PhoneActionResponse) {
        when (response.result) {
            PhoneActionResult.OK -> clearOperation(operation)
            PhoneActionResult.RETRYABLE_ERROR,
            PhoneActionResult.UNAVAILABLE -> retryableError(operation, "No se pudo completar. Intenta de nuevo.")
            PhoneActionResult.NOT_AUTHENTICATED -> terminalError(operation, "Abre MotoSOS en tu teléfono e inicia sesión.")
            PhoneActionResult.WRONG_ROLE -> terminalError(operation, "Esta función requiere una cuenta de motociclista.")
            PhoneActionResult.PHONE_ACTION_REQUIRED -> terminalError(operation, "Revisa MotoSOS en tu teléfono.")
            PhoneActionResult.NO_ACTIVE_TRIP,
            PhoneActionResult.TRIP_MISMATCH -> {
                clearOperation(operation)
                actions.refreshTripState()
                mutableActionState.value = WearTripUiActionState.Error(
                    operation,
                    "El estado del viaje cambió en el teléfono.",
                    retryable = false
                )
            }
            PhoneActionResult.REJECTED,
            PhoneActionResult.INVALID_REQUEST -> terminalError(operation, "No fue posible completar la acción.")
        }
    }

    private fun retryableError(operation: WearTripUiOperation, message: String) {
        mutableActionState.value = WearTripUiActionState.Error(operation, message, retryable = true)
    }

    private fun terminalError(operation: WearTripUiOperation, message: String) {
        clearOperation(operation)
        mutableActionState.value = WearTripUiActionState.Error(operation, message, retryable = false)
    }

    private fun clearOperation(operation: WearTripUiOperation) {
        when (operation) {
            WearTripUiOperation.Start -> pendingStartCommandId = null
            WearTripUiOperation.Finish -> pendingFinishCommandId = null
        }
        mutableActionState.value = WearTripUiActionState.Idle
    }
}
