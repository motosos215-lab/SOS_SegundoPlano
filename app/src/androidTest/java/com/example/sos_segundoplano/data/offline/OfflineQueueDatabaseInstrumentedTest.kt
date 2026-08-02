package com.example.sos_segundoplano.data.offline

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.sos_segundoplano.domain.offline.OfflineEventType
import com.example.sos_segundoplano.domain.offline.OfflineQueueStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.KeyStore

class OfflineQueueDatabaseInstrumentedTest {
    private var database: OfflineQueueDatabase? = null

    @After fun closeDatabase() {
        database?.close()
        database = null
    }

    @Test fun insertUniqueClaimMarkSentRetryRecoverAndErrors() = runBlocking {
        val db = inMemoryDatabase()
        val dao = db.offlineQueueDao()
        val errors = db.syncErrorDao()
        val entity = entity("minor-event:1:v1")

        val firstId = dao.insertIgnore(entity)
        val duplicateId = dao.insertIgnore(entity)
        assertTrue(firstId > 0L)
        assertEquals(-1L, duplicateId)

        val firstClaim = dao.claimReadyBatch("worker-a", 1_000L, 10)
        val secondClaim = dao.claimReadyBatch("worker-b", 1_000L, 10)
        assertEquals(1, firstClaim.size)
        assertTrue(secondClaim.isEmpty())

        val first = firstClaim.single()
        dao.markRetry(firstId, "worker-a", first.attemptCount, requireNotNull(first.claimedAtEpochMillis), requireNotNull(first.claimToken), "Transport", "timeout", "temporary", 2_000L, 1_100L)
        assertEquals(OfflineQueueStatus.RetryPending.name, dao.getById(firstId)?.status)
        val sentClaim = dao.claimReadyBatch("worker-c", 2_000L, 10).single()
        dao.markSent(firstId, "worker-c", sentClaim.attemptCount, requireNotNull(sentClaim.claimedAtEpochMillis), requireNotNull(sentClaim.claimToken), "ack-1", 2_100L)
        assertEquals(OfflineQueueStatus.Sent.name, dao.getById(firstId)?.status)
        dao.markSent(firstId, "worker-c", sentClaim.attemptCount, requireNotNull(sentClaim.claimedAtEpochMillis), requireNotNull(sentClaim.claimToken), "ack-2", 2_200L)
        assertEquals("ack-1", dao.getById(firstId)?.ackSanitized)

        val stuckId = dao.insertIgnore(entity("local-incident:2:v1"))
        val claimedAt = 3_000L
        val leaseTimeout = 2_000L
        val recoveryNow = claimedAt + leaseTimeout
        val stuckClaim = dao.claimReadyBatch("worker-d", claimedAt, 10).single { it.queueItemId == stuckId }
        assertEquals("worker-d", stuckClaim.claimedBy)
        assertEquals(claimedAt, requireNotNull(stuckClaim.claimedAtEpochMillis))
        requireNotNull(stuckClaim.claimToken)

        assertEquals(1, dao.recoverAbandoned(recoveryNow - leaseTimeout, recoveryNow))
        val recovered = requireNotNull(dao.getById(stuckId))
        assertEquals(OfflineQueueStatus.RetryPending.name, recovered.status)
        assertEquals(null, recovered.claimedBy)
        assertEquals(null, recovered.claimedAtEpochMillis)
        assertEquals(null, recovered.claimToken)

        errors.insertAndTrim(error(stuckId, 1L), maxRecords = 1)
        errors.insertAndTrim(error(stuckId, 2L), maxRecords = 1)
        assertEquals(1, errors.latest(10).size)
        assertEquals(1, dao.deleteSentBefore(3_000L))
    }

    @Test fun concurrentInsertOfSameIdempotencyKeyCreatesOneRecord() = runBlocking {
        val dao = inMemoryDatabase().offlineQueueDao()
        val results = (1..8).map {
            async(Dispatchers.Default) { dao.insertIgnore(entity("alert-dispatch-request:9:v1")) }
        }.awaitAll()

        assertEquals(1, results.count { it > 0L })
        assertEquals(7, results.count { it == -1L })
    }

    @Test fun persistentDatabaseSurvivesCloseAndReopen() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val name = "offline_queue_test.db"
            context.deleteDatabase(name)
            val firstDb = Room.databaseBuilder(context, OfflineQueueDatabase::class.java, name).build()
            database = firstDb
            val id = firstDb.offlineQueueDao().insertIgnore(entity("minor-event:77:v1"))
            firstDb.close()

            val reopenedDb = Room.databaseBuilder(context, OfflineQueueDatabase::class.java, name).build()
            database = reopenedDb
            val restored = reopenedDb.offlineQueueDao().getById(id)

            assertEquals("minor-event:77:v1", restored?.idempotencyKey)
            reopenedDb.close()
            database = null
            context.deleteDatabase(name)
        }
    }

    @Test fun staleClaimCannotOverwriteNewClaimAck() = runBlocking {
        val dao = inMemoryDatabase().offlineQueueDao()
        val id = dao.insertIgnore(entity("minor-event:88:v1"))
        val claimA = dao.claimReadyBatch("worker-a", 1_000L, 10).single()
        assertEquals(1, dao.recoverAbandoned(1_000L, 2_000L))
        val claimB = dao.claimReadyBatch("worker-b", 2_000L, 10).single()

        assertEquals(0, dao.markSent(id, "worker-a", claimA.attemptCount, requireNotNull(claimA.claimedAtEpochMillis), requireNotNull(claimA.claimToken), "ack-a", 2_100L))
        assertEquals(0, dao.markRetry(id, "worker-a", claimA.attemptCount, requireNotNull(claimA.claimedAtEpochMillis), requireNotNull(claimA.claimToken), "Transport", "timeout", "old", 3_000L, 2_100L))
        assertEquals(0, dao.markPermanentFailure(id, "worker-a", claimA.attemptCount, requireNotNull(claimA.claimedAtEpochMillis), requireNotNull(claimA.claimToken), "Transport", "bad", "old", 2_100L))
        assertEquals(1, dao.markSent(id, "worker-b", claimB.attemptCount, requireNotNull(claimB.claimedAtEpochMillis), requireNotNull(claimB.claimToken), "ack-b", 2_200L))
        assertEquals("ack-b", dao.getById(id)?.ackSanitized)
    }

    @Test fun insertBundleConfirmsBothRecordsExist() = runBlocking {
        val dao = inMemoryDatabase().offlineQueueDao()
        val incident = entity("local-incident:10:v1")
        val request = entity("alert-dispatch-request:10:v1")
        val both = dao.insertBundle(incident, request) as BundleInsertResult.Persisted
        assertTrue(both.incidentQueueItemId > 0L)
        assertTrue(both.requestQueueItemId > 0L)
        assertTrue(dao.insertBundle(incident, request) is BundleInsertResult.Persisted)
    }

    @Test fun lastAttemptAtOnlyChangesOnClaim() = runBlocking {
        val dao = inMemoryDatabase().offlineQueueDao()
        val id = dao.insertIgnore(entity("minor-event:99:v1"))
        assertEquals(null, dao.getById(id)?.lastAttemptAtEpochMillis)
        val claim = dao.claimReadyBatch("worker", 5_000L, 10).single()
        assertEquals(5_000L, dao.getById(id)?.lastAttemptAtEpochMillis)
        dao.markSent(id, "worker", claim.attemptCount, requireNotNull(claim.claimedAtEpochMillis), requireNotNull(claim.claimToken), "ack", 6_000L)
        assertEquals(5_000L, dao.getById(id)?.lastAttemptAtEpochMillis)
    }

    @Test fun androidKeystoreEncryptsRoundTripAndRejectsTampering() {
        val testAlias = "motosos_offline_queue_test_${System.nanoTime()}"
        deleteKeystoreAlias(testAlias)
        try {
            val crypto = AndroidKeystoreAesGcmCrypto(
                keyVersion = AndroidKeystoreAesGcmCrypto.KEY_VERSION,
                keyAlias = testAlias
            )
            val aad = OfflineQueueAssociatedData("minor-event:1:v1", OfflineEventType.MinorEvent.wireName, 1, AndroidKeystoreAesGcmCrypto.KEY_VERSION)
            val plaintext = "plain-payload".toByteArray()
            val encrypted = requireEncryptionSuccess(crypto.encrypt(plaintext, aad))
            val decrypted = requireDecryptionSuccess(crypto.decrypt(encrypted.ciphertext, encrypted.nonce, aad))
            val tamperedCiphertext = encrypted.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
            val wrongAad = aad.copy(idempotencyKey = "minor-event:wrong:v1")

            assertArrayEquals(plaintext, decrypted)
            assertFalse(String(encrypted.ciphertext, Charsets.ISO_8859_1).contains("plain-payload"))
            assertTrue(crypto.decrypt(tamperedCiphertext, encrypted.nonce, aad) is OfflineCryptoResult.Failure)
            assertTrue(crypto.decrypt(encrypted.ciphertext, encrypted.nonce, wrongAad) is OfflineCryptoResult.Failure)
        } finally {
            deleteKeystoreAlias(testAlias)
        }
    }

    private fun inMemoryDatabase(): OfflineQueueDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        OfflineQueueDatabase::class.java
    ).build().also { database = it }

    private fun requireEncryptionSuccess(result: OfflineCryptoResult<EncryptedPayload>): EncryptedPayload = when (result) {
        is OfflineCryptoResult.Success -> result.value
        is OfflineCryptoResult.Failure -> failCrypto("Encryption failed: category=${result.category}, message=${result.sanitizedMessage}")
    }

    private fun requireDecryptionSuccess(result: OfflineCryptoResult<ByteArray>): ByteArray = when (result) {
        is OfflineCryptoResult.Success -> result.value
        is OfflineCryptoResult.Failure -> failCrypto("Decryption failed: category=${result.category}, message=${result.sanitizedMessage}")
    }

    private fun failCrypto(message: String): Nothing {
        fail(message)
        throw AssertionError(message)
    }

    private fun deleteKeystoreAlias(alias: String) {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
        }
    }

    private fun entity(key: String): OfflineQueueEntity = OfflineQueueEntity(
        idempotencyKey = key,
        eventType = key.substringBefore(':'),
        priority = OfflineEventType.MinorEvent.priority,
        payloadSchemaVersion = 1,
        encryptedPayload = byteArrayOf(4, 5, 6),
        encryptionNonce = ByteArray(12) { it.toByte() },
        encryptionKeyVersion = 1,
        sourceSessionId = 1L,
        sourceAssessmentId = 2L,
        sourceEventId = key.substringAfter(':').substringBefore(':'),
        occurredAtEpochMillis = 100L,
        enqueuedAtEpochMillis = 100L,
        updatedAtEpochMillis = 100L,
        lastAttemptAtEpochMillis = null,
        nextAttemptAtEpochMillis = null,
        sentAtEpochMillis = null,
        attemptCount = 0,
        status = OfflineQueueStatus.Pending.name,
        claimedAtEpochMillis = null,
        claimedBy = null,
        claimToken = null,
        lastErrorCategory = null,
        lastErrorCode = null,
        lastErrorMessageSanitized = null,
        ackSanitized = null
    )

    private fun error(queueItemId: Long, at: Long): SyncErrorEntity = SyncErrorEntity(
        queueItemId = queueItemId,
        idempotencyKey = "key-$at",
        eventType = OfflineEventType.MinorEvent.wireName,
        category = "Transport",
        code = "timeout",
        sanitizedMessage = "temporary",
        attemptNumber = 1,
        occurredAtEpochMillis = at,
        isPermanent = false
    )
}
