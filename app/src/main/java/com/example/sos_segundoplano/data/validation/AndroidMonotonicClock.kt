package com.example.sos_segundoplano.data.validation

import android.os.SystemClock
import com.example.sos_segundoplano.domain.validation.MonotonicClock

object AndroidMonotonicClock : MonotonicClock {
    override fun elapsedRealtimeNanos(): Long = SystemClock.elapsedRealtimeNanos()
}
