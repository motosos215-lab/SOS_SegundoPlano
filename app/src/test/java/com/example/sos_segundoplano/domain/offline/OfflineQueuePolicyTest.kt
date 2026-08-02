package com.example.sos_segundoplano.domain.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineQueuePolicyTest {
    @Test fun idempotencyKeyIsDeterministicAndSchemaVersioned() {
        assertEquals("minor-event:123:v1", idempotencyKey(OfflineEventType.MinorEvent, "123", 1))
        assertEquals("local-incident:456:v1", idempotencyKey(OfflineEventType.LocalIncident, "456", 1))
        assertEquals("alert-dispatch-request:789:v1", idempotencyKey(OfflineEventType.AlertDispatchRequest, "789", 1))
    }

    @Test fun exponentialBackoffIsCappedAndCanUseRetryAfter() {
        val policy = OfflineQueuePolicy(OfflineQueueConfig(initialBackoffMillis = 1_000L, maxBackoffMillis = 5_000L))
        assertEquals(11_000L, policy.nextRetryAt(10_000L, 1))
        assertEquals(12_000L, policy.nextRetryAt(10_000L, 2))
        assertEquals(15_000L, policy.nextRetryAt(10_000L, 8))
        assertEquals(17_000L, policy.nextRetryAt(10_000L, 8, retryAfterMillis = 7_000L))
    }

    @Test fun retryPolicyStopsAtMaxAttemptsAndComputesAbandonedCutoff() {
        val policy = OfflineQueuePolicy(OfflineQueueConfig(maxAttempts = 3, inFlightLeaseTimeoutMillis = 50L))
        assertTrue(policy.shouldRetry(2))
        assertFalse(policy.shouldRetry(3))
        assertEquals(950L, policy.abandonedInFlightCutoff(1_000L))
    }

    @Test fun transportResultsSeparateAckTransientPermanentAndNotConfigured() {
        val ack = OfflineEventTransportResult.Acknowledged("ack-1")
        val transient = OfflineEventTransportResult.TransientFailure(OfflineSyncErrorCategory.Transport, "timeout", "temporary", 1_000L)
        val permanent = OfflineEventTransportResult.PermanentFailure(OfflineSyncErrorCategory.Serialization, "schema", "unsupported")
        val notConfigured = OfflineEventTransportResult.NotConfigured()

        assertEquals("ack-1", ack.ackId)
        assertEquals(1_000L, transient.retryAfterMillis)
        assertEquals(OfflineSyncErrorCategory.Serialization, permanent.category)
        assertEquals("offline_transport_not_configured", notConfigured.sanitizedMessage)
    }
}
