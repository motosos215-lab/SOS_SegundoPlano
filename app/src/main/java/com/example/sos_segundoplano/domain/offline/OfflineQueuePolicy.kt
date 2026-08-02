package com.example.sos_segundoplano.domain.offline

import kotlin.math.min

class OfflineQueuePolicy(
    private val config: OfflineQueueConfig = OfflineQueueConfig(),
    private val jitter: (Int) -> Long = { 0L }
) {
    fun nextRetryAt(nowMillis: Long, nextAttemptNumber: Int, retryAfterMillis: Long? = null): Long {
        val retryAfter = retryAfterMillis?.takeIf { it > 0L }
        if (retryAfter != null) return nowMillis + retryAfter
        val exponent = (nextAttemptNumber - 1).coerceAtLeast(0).coerceAtMost(30)
        val multiplier = 1L shl exponent
        val rawDelay = safeMultiply(config.initialBackoffMillis, multiplier)
        return nowMillis + min(config.maxBackoffMillis, rawDelay) + jitter(nextAttemptNumber).coerceAtLeast(0L)
    }

    fun shouldRetry(attemptCount: Int): Boolean = attemptCount < config.maxAttempts

    fun abandonedInFlightCutoff(nowMillis: Long): Long = nowMillis - config.inFlightLeaseTimeoutMillis

    private fun safeMultiply(value: Long, multiplier: Long): Long =
        if (value > Long.MAX_VALUE / multiplier) Long.MAX_VALUE else value * multiplier
}
