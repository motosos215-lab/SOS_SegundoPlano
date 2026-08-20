package com.example.sos_segundoplano.wear

import com.example.sos_segundoplano.wearprotocol.TripStateResponse
import com.example.sos_segundoplano.wearprotocol.PhoneActionResponse
import com.example.sos_segundoplano.wearprotocol.PhoneActionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class WearTripState(val active: Boolean? = null, val remoteTripId: String? = null, val startedAtEpochMs: Long? = null, val updatedAtEpochMs: Long? = null, val connected: Boolean? = null)

class WearTripStateStore {
    private val mutableState = MutableStateFlow(WearTripState())
    val state: StateFlow<WearTripState> = mutableState
    fun confirm(response: TripStateResponse) { mutableState.value = WearTripState(response.active, response.remoteTripId, response.startedAtEpochMs, response.updatedAtEpochMs, true) }
    fun markDisconnected() { mutableState.value = mutableState.value.copy(connected = false) }
}

internal class WearTripStateReconciler(private val gateway: MobileCompanionClient, private val store: WearTripStateStore) {
    suspend fun refreshTripState(): MobileCompanionResult<TripStateResponse> = gateway.getTripState().also { if (it is MobileCompanionResult.Success) store.confirm(it.value) else store.markDisconnected() }
    suspend fun startTrip(commandId: String): MobileCompanionResult<PhoneActionResponse> = reconcileAfter(gateway.startTrip(commandId))
    suspend fun finishTrip(commandId: String): MobileCompanionResult<PhoneActionResponse> {
        val tripId = store.state.value.remoteTripId ?: return MobileCompanionResult.CompanionUnavailable
        return finishTrip(commandId, tripId)
    }
    suspend fun finishTrip(commandId: String, remoteTripId: String): MobileCompanionResult<PhoneActionResponse> =
        reconcileAfter(gateway.finishTrip(commandId, remoteTripId))
    suspend fun manualSos(commandId: String): MobileCompanionResult<PhoneActionResponse> {
        val tripId = store.state.value.remoteTripId ?: return MobileCompanionResult.CompanionUnavailable
        return reconcileAfter(gateway.manualSos(commandId, tripId))
    }
    private suspend fun reconcileAfter(result: MobileCompanionResult<PhoneActionResponse>): MobileCompanionResult<PhoneActionResponse> {
        if (result is MobileCompanionResult.Success && result.value.result == PhoneActionResult.OK) refreshTripState()
        return result
    }
}
