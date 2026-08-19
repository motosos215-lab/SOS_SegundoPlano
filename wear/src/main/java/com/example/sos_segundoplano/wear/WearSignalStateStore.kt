package com.example.sos_segundoplano.wear

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory view of the one physical signal pipeline.  The foreground service is the only
 * producer; RPC and Compose only observe this store, so they never register sensors themselves.
 */
object WearSignalStateStore {
    private val mutableSnapshot = MutableStateFlow(WearSignalSnapshot())
    val snapshot: StateFlow<WearSignalSnapshot> = mutableSnapshot.asStateFlow()

    fun update(value: WearSignalSnapshot) {
        mutableSnapshot.value = value
    }
}
