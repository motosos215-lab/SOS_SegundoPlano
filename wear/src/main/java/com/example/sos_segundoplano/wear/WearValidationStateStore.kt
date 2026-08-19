package com.example.sos_segundoplano.wear

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object WearValidationStateStore {
    private val mutableState = MutableStateFlow<WearDataLayerProtocol.ValidationStatus?>(null)
    val state: StateFlow<WearDataLayerProtocol.ValidationStatus?> = mutableState

    fun confirm(status: WearDataLayerProtocol.ValidationStatus) {
        mutableState.value = status
    }

    internal fun resetForTest() {
        mutableState.value = null
    }
}

internal object WearValidationStatusReceiver {
    fun handle(payload: ByteArray): Boolean {
        val status = WearDataLayerProtocol.decodeValidationStatusOrNull(payload) ?: return false
        WearValidationStateStore.confirm(status)
        return true
    }
}
