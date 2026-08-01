package com.example.sos_segundoplano.wear

class WearTransmissionLimiter(private val intervalMillis: Long = 1_000L) {
    private var lastSentMillis = 0L

    fun shouldSend(nowMillis: Long): Boolean {
        if (nowMillis - lastSentMillis < intervalMillis) return false
        lastSentMillis = nowMillis
        return true
    }

    fun reset() {
        lastSentMillis = 0L
    }
}
