package com.example.sos_segundoplano.core.push

import android.content.Context
import com.example.sos_segundoplano.data.local.push.SharedPreferencesRiderMonitorFeedbackStore
import com.example.sos_segundoplano.domain.push.RiderMonitorFeedbackCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object RiderMonitorFeedbackProvider {
    @Volatile
    private var coordinator: RiderMonitorFeedbackCoordinator? = null
    private val mutableOpenRequestRevision = MutableStateFlow(0L)
    val openRequestRevision: StateFlow<Long> = mutableOpenRequestRevision.asStateFlow()

    fun initialize(context: Context): RiderMonitorFeedbackCoordinator = get(context)

    fun get(context: Context): RiderMonitorFeedbackCoordinator = coordinator ?: synchronized(this) {
        coordinator ?: RiderMonitorFeedbackCoordinator(
            SharedPreferencesRiderMonitorFeedbackStore(context.applicationContext)
        ).also { coordinator = it }
    }

    fun requestOpenMessages() {
        mutableOpenRequestRevision.value = mutableOpenRequestRevision.value + 1L
    }
}
