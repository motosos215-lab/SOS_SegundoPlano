package com.example.sos_segundoplano.data.offline

import com.example.sos_segundoplano.domain.offline.ClaimedOfflineQueueItem
import com.example.sos_segundoplano.domain.offline.MinorEventSyncPayload
import com.example.sos_segundoplano.domain.offline.OfflineEventTransport
import com.example.sos_segundoplano.domain.offline.OfflineEventTransportResult
import com.example.sos_segundoplano.domain.offline.OfflineEventType
import com.example.sos_segundoplano.domain.offline.OfflineQueueClaim
import com.example.sos_segundoplano.domain.offline.OfflineQueueConfig
import com.example.sos_segundoplano.domain.offline.OfflineQueueItem
import com.example.sos_segundoplano.domain.offline.OfflineQueuePolicy
import com.example.sos_segundoplano.domain.offline.OfflineQueueStatus
import com.example.sos_segundoplano.domain.offline.OfflineQueueSyncResult
import com.example.sos_segundoplano.domain.offline.OfflineQueueTransitionResult
import com.example.sos_segundoplano.domain.offline.OfflineSyncErrorCategory
import com.example.sos_segundoplano.domain.offline.OfflineSyncPayload
import com.example.sos_segundoplano.domain.offline.WallClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineQueueSyncProcessorTest {
    @Test fun emptyBatchWithoutPendingReturnsNothingToDo() = runBlocking {
        val repo = FakeSyncRepository()
        assertEquals(OfflineQueueSyncResult.NothingToDo, processor(repo).process("worker"))
    }

    @Test fun acknowledgedMarksSent() = runBlocking {
        val repo = FakeSyncRepository(claimed = listOf(claimed()))
        val result = processor(repo, transport = FakeTransport(OfflineEventTransportResult.Acknowledged("ack"))).process("worker")
        assertEquals(OfflineQueueSyncResult.Completed, result)
        assertEquals(1, repo.sent)
    }

    @Test fun emptyAckAndTransientFailureMarkRetry() = runBlocking {
        val repo = FakeSyncRepository(claimed = listOf(claimed(), claimed(2L)))
        val transport = object : OfflineEventTransport {
            var index = 0
            override suspend fun send(item: OfflineQueueItem, payload: OfflineSyncPayload): OfflineEventTransportResult =
                if (index++ == 0) OfflineEventTransportResult.Acknowledged("") else OfflineEventTransportResult.TransientFailure(OfflineSyncErrorCategory.Transport, "timeout", "temporary", 5_000L)
        }
        assertEquals(OfflineQueueSyncResult.RetryRequired, processor(repo, transport).process("worker"))
        assertEquals(2, repo.retried)
    }

    @Test fun maxAttemptsAndPermanentFailureMarkPermanent() = runBlocking {
        val repo = FakeSyncRepository(claimed = listOf(claimed(attempt = 8), claimed(2L)))
        val transport = object : OfflineEventTransport {
            var index = 0
            override suspend fun send(item: OfflineQueueItem, payload: OfflineSyncPayload): OfflineEventTransportResult =
                if (index++ == 0) OfflineEventTransportResult.TransientFailure(OfflineSyncErrorCategory.Transport, "timeout", "temporary", null)
                else OfflineEventTransportResult.PermanentFailure(OfflineSyncErrorCategory.Transport, "bad", "permanent")
        }
        assertEquals(OfflineQueueSyncResult.Completed, processor(repo, transport).process("worker"))
        assertEquals(2, repo.permanent)
    }

    @Test fun notConfiguredNeverMarksSentOrPermanent() = runBlocking {
        val repo = FakeSyncRepository(claimed = listOf(claimed()))
        val result = processor(repo, FakeTransport(OfflineEventTransportResult.NotConfigured("remote_not_configured"))).process("worker")
        assertTrue(result is OfflineQueueSyncResult.DeferredUntil)
        assertTrue((result as OfflineQueueSyncResult.DeferredUntil).epochMillis > 1_000L)
        assertEquals(1, repo.notConfigured)
        assertEquals(0, repo.sent)
        assertEquals(0, repo.permanent)
    }

    @Test fun decryptFailureMarksPermanentAndContinues() = runBlocking {
        val repo = FakeSyncRepository(claimed = listOf(claimed(), claimed(2L)), decryptFailure = true)
        processor(repo).process("worker")
        assertEquals(2, repo.permanent)
    }

    @Test fun staleClaimIsNotCountedAsApplied() = runBlocking {
        val repo = FakeSyncRepository(claimed = listOf(claimed()), transition = OfflineQueueTransitionResult.StaleClaim)
        processor(repo, FakeTransport(OfflineEventTransportResult.Acknowledged("ack"))).process("worker")
        assertEquals(1, repo.sentCalls)
        assertEquals(0, repo.appliedSent)
    }

    private fun processor(
        repo: FakeSyncRepository,
        transport: OfflineEventTransport = FakeTransport(OfflineEventTransportResult.Acknowledged("ack"))
    ) = OfflineQueueSyncProcessor(repo, transport, OfflineQueuePolicy(OfflineQueueConfig(maxAttempts = 8)), WallClock { 1_000L }, OfflineQueueConfig(maxAttempts = 8))

    private class FakeTransport(private val result: OfflineEventTransportResult) : OfflineEventTransport {
        override suspend fun send(item: OfflineQueueItem, payload: OfflineSyncPayload): OfflineEventTransportResult = result
    }

    private class FakeSyncRepository(
        private val claimed: List<ClaimedOfflineQueueItem> = emptyList(),
        private val decryptFailure: Boolean = false,
        private val transition: OfflineQueueTransitionResult = OfflineQueueTransitionResult.Applied,
        private val decryptedPayload: OfflineSyncPayload = OfflineSyncPayload.MinorEventPayload(
            MinorEventSyncPayload(
                eventId = 1L,
                sessionId = 1L,
                assessmentId = 1L,
                windowId = 1L,
                type = "Bump",
                score = null,
                confidence = 0.8,
                policyVersion = "policy",
                occurredAtEpochMillis = 1_000L,
                createdAtElapsedRealtimeNanos = 1_000L
            )
        )
    ) : OfflineQueueSyncRepository {
        var sent = 0
        var sentCalls = 0
        var appliedSent = 0
        var retried = 0
        var notConfigured = 0
        var permanent = 0
        override suspend fun recoverAbandonedItems(nowMillis: Long): Int = 0
        override suspend fun cleanSentBefore(cutoffEpochMillis: Long): Int = 0
        override suspend fun claimReadyBatch(workerId: String, nowMillis: Long): List<ClaimedOfflineQueueItem> = claimed
        override suspend fun decryptPayload(item: ClaimedOfflineQueueItem): OfflineCryptoResult<OfflineSyncPayload> =
            if (decryptFailure) OfflineCryptoResult.Failure(OfflineSyncErrorCategory.Encryption, "decrypt") else OfflineCryptoResult.Success(decryptedPayload)
        override suspend fun markSent(claim: OfflineQueueClaim, ackSanitized: String, nowMillis: Long): OfflineQueueTransitionResult {
            sentCalls++
            if (transition == OfflineQueueTransitionResult.Applied) {
                sent++
                appliedSent++
            }
            return transition
        }
        override suspend fun markRetry(claim: OfflineQueueClaim, category: OfflineSyncErrorCategory, code: String?, sanitizedMessage: String?, nextAttemptAtMillis: Long, nowMillis: Long): OfflineQueueTransitionResult { retried++; assertTrue(nextAttemptAtMillis >= nowMillis); return transition }
        override suspend fun markNotConfigured(claim: OfflineQueueClaim, code: String?, sanitizedMessage: String?, nextAttemptAtMillis: Long, nowMillis: Long): OfflineQueueTransitionResult { notConfigured++; return transition }
        override suspend fun markPermanentFailure(claim: OfflineQueueClaim, category: OfflineSyncErrorCategory, code: String?, sanitizedMessage: String?, nowMillis: Long): OfflineQueueTransitionResult { permanent++; return transition }
        override suspend fun hasUnfinishedWork(): Boolean = claimed.isNotEmpty()
        override suspend fun earliestPendingAttemptAt(nowMillis: Long): Long? = null
    }

    private fun claimed(id: Long = 1L, attempt: Int = 1): ClaimedOfflineQueueItem = ClaimedOfflineQueueItem(
        item = OfflineQueueItem(id, "minor-event:$id:v1", OfflineEventType.MinorEvent, 1, 1L, 1L, id.toString(), 1_000L, 1_000L, 1_000L, null, 1_000L, null, attempt, OfflineQueueStatus.InFlight, null, null, null, null),
        encryptedPayload = byteArrayOf(1),
        encryptionNonce = ByteArray(12),
        encryptionKeyVersion = 1,
        claim = OfflineQueueClaim(id, "worker", attempt, 1_000L, "token-$id")
    )
}
