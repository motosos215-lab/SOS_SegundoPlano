package com.example.sos_segundoplano.domain.offline

sealed interface OfflineEventTransportResult {
    data class Acknowledged(val ackId: String) : OfflineEventTransportResult
    data class TransientFailure(
        val category: OfflineSyncErrorCategory,
        val code: String? = null,
        val sanitizedMessage: String? = null,
        val retryAfterMillis: Long? = null
    ) : OfflineEventTransportResult

    data class PermanentFailure(
        val category: OfflineSyncErrorCategory,
        val code: String? = null,
        val sanitizedMessage: String? = null
    ) : OfflineEventTransportResult

    data class NotConfigured(val sanitizedMessage: String = "offline_transport_not_configured") : OfflineEventTransportResult
}

interface OfflineEventTransport {
    suspend fun send(item: OfflineQueueItem, payload: OfflineSyncPayload): OfflineEventTransportResult
}
