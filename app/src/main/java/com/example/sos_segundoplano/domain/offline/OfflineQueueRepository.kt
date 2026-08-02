package com.example.sos_segundoplano.domain.offline

import com.example.sos_segundoplano.domain.validation.AlertDispatchRequest
import com.example.sos_segundoplano.domain.validation.LocalIncident
import com.example.sos_segundoplano.domain.validation.MinorEvent
import kotlinx.coroutines.flow.Flow

interface OfflineQueueRepository : OfflineEventSink {
    override suspend fun enqueueMinorEvent(event: MinorEvent): OfflineQueueEnqueueResult
    override suspend fun enqueueIncident(incident: LocalIncident): OfflineQueueEnqueueResult
    override suspend fun enqueueAlertRequest(request: AlertDispatchRequest): OfflineQueueEnqueueResult
    override suspend fun enqueueIncidentBundle(incident: LocalIncident, request: AlertDispatchRequest): OfflineQueueEnqueueResult
    suspend fun claimReadyBatch(workerId: String, nowMillis: Long): List<ClaimedOfflineQueueItem>
    suspend fun markSent(claim: OfflineQueueClaim, ackSanitized: String, nowMillis: Long): OfflineQueueTransitionResult
    suspend fun markRetry(claim: OfflineQueueClaim, category: OfflineSyncErrorCategory, code: String?, sanitizedMessage: String?, nextAttemptAtMillis: Long, nowMillis: Long): OfflineQueueTransitionResult
    suspend fun markNotConfigured(claim: OfflineQueueClaim, code: String?, sanitizedMessage: String?, nextAttemptAtMillis: Long, nowMillis: Long): OfflineQueueTransitionResult
    suspend fun markPermanentFailure(claim: OfflineQueueClaim, category: OfflineSyncErrorCategory, code: String?, sanitizedMessage: String?, nowMillis: Long): OfflineQueueTransitionResult
    suspend fun recoverAbandonedItems(nowMillis: Long): Int
    fun observeSummary(): Flow<OfflineQueueSummary>
    suspend fun getErrors(limit: Int): List<SyncErrorRecord>
    suspend fun cleanSentBefore(cutoffEpochMillis: Long): Int
}
