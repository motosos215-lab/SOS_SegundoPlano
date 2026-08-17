package com.example.sos_segundoplano.data.offline

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.sos_segundoplano.domain.offline.OfflineEventType
import com.example.sos_segundoplano.domain.offline.OfflineQueueStatus
import com.example.sos_segundoplano.domain.offline.OfflineQueueConfig
import com.example.sos_segundoplano.domain.offline.WallClock
import com.example.sos_segundoplano.domain.offline.isPersisted
import com.example.sos_segundoplano.domain.rules.GpsQualityStatus
import com.example.sos_segundoplano.domain.rules.RiskLevel
import com.example.sos_segundoplano.domain.validation.AlertDispatchRequest
import com.example.sos_segundoplano.domain.validation.AlertPayloadSummary
import com.example.sos_segundoplano.domain.validation.AlertPriority
import com.example.sos_segundoplano.domain.validation.IncidentCause
import com.example.sos_segundoplano.domain.validation.LocalIncident
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.KeyStore
import javax.crypto.spec.SecretKeySpec

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

    @Test fun migrationFromVersionOnePreservesLegacyRowsWithoutAssigningOwnerOrBundle() {
        runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "offline_queue_migration_test.db"
        context.deleteDatabase(name)
        context.openOrCreateDatabase(name, android.content.Context.MODE_PRIVATE, null).use { legacy ->
            legacy.execSQL(
                "CREATE TABLE offline_queue_items (queueItemId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "idempotencyKey TEXT NOT NULL, eventType TEXT NOT NULL, priority INTEGER NOT NULL, " +
                    "payloadSchemaVersion INTEGER NOT NULL, encryptedPayload BLOB NOT NULL, encryptionNonce BLOB NOT NULL, " +
                    "encryptionKeyVersion INTEGER NOT NULL, sourceSessionId INTEGER, sourceAssessmentId INTEGER, " +
                    "sourceEventId TEXT NOT NULL, occurredAtEpochMillis INTEGER NOT NULL, enqueuedAtEpochMillis INTEGER NOT NULL, " +
                    "updatedAtEpochMillis INTEGER NOT NULL, lastAttemptAtEpochMillis INTEGER, nextAttemptAtEpochMillis INTEGER, " +
                    "sentAtEpochMillis INTEGER, attemptCount INTEGER NOT NULL, status TEXT NOT NULL, claimedAtEpochMillis INTEGER, " +
                    "claimedBy TEXT, claimToken TEXT, lastErrorCategory TEXT, lastErrorCode TEXT, " +
                    "lastErrorMessageSanitized TEXT, ackSanitized TEXT)"
            )
            legacy.execSQL("CREATE UNIQUE INDEX index_offline_queue_items_idempotencyKey ON offline_queue_items(idempotencyKey)")
            legacy.execSQL("CREATE INDEX index_offline_queue_items_status_nextAttemptAtEpochMillis_priority_occurredAtEpochMillis ON offline_queue_items(status, nextAttemptAtEpochMillis, priority, occurredAtEpochMillis)")
            legacy.execSQL("CREATE INDEX index_offline_queue_items_claimedAtEpochMillis ON offline_queue_items(claimedAtEpochMillis)")
            legacy.execSQL(
                "CREATE TABLE offline_sync_errors (errorId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, queueItemId INTEGER NOT NULL, " +
                    "idempotencyKey TEXT NOT NULL, eventType TEXT NOT NULL, category TEXT NOT NULL, code TEXT, sanitizedMessage TEXT, " +
                    "attemptNumber INTEGER NOT NULL, occurredAtEpochMillis INTEGER NOT NULL, isPermanent INTEGER NOT NULL)"
            )
            legacy.execSQL("CREATE INDEX index_offline_sync_errors_queueItemId ON offline_sync_errors(queueItemId)")
            legacy.execSQL("CREATE INDEX index_offline_sync_errors_occurredAtEpochMillis ON offline_sync_errors(occurredAtEpochMillis)")
            legacy.execSQL("INSERT INTO offline_queue_items (idempotencyKey, eventType, priority, payloadSchemaVersion, encryptedPayload, encryptionNonce, encryptionKeyVersion, sourceEventId, occurredAtEpochMillis, enqueuedAtEpochMillis, updatedAtEpochMillis, attemptCount, status) VALUES ('legacy:v1', 'local-incident', 20, 1, X'01', X'010203040506070809101112', 1, 'legacy', 1, 1, 1, 0, 'Pending')")
            legacy.version = 1
        }

        val migrated = Room.databaseBuilder(context, OfflineQueueDatabase::class.java, name)
            .addMigrations(OfflineQueueDatabase.MIGRATION_1_2)
            .build()
        database = migrated
        val legacy = migrated.offlineQueueDao().getByIdempotencyKey("legacy:v1")

        assertNull(legacy?.ownerUserId)
        assertNull(legacy?.bundleKey)
        migrated.close()
        database = null
        context.deleteDatabase(name)
        }
    }

    @Test fun ownerScopedBundleLookupNeverReturnsAnotherRiderOrLegacyRows() = runBlocking {
        val dao = inMemoryDatabase().offlineQueueDao()
        val ownerAIncident = entity("local-incident:owner-a:v1").copy(ownerUserId = "rider-a", bundleKey = "bundle-a")
        val ownerARequest = entity("alert-dispatch-request:owner-a:v1").copy(ownerUserId = "rider-a", bundleKey = "bundle-a")
        val ownerBIncident = entity("local-incident:owner-b:v1").copy(ownerUserId = "rider-b", bundleKey = "bundle-b")
        val legacy = entity("local-incident:legacy:v1")

        dao.insertBundle(ownerAIncident, ownerARequest)
        dao.insertIgnore(ownerBIncident)
        dao.insertIgnore(legacy)

        assertEquals(2, dao.bundleForOwner("rider-a", "bundle-a").size)
        assertTrue(dao.bundleForOwner("rider-b", "bundle-a").isEmpty())
        assertEquals(listOf("bundle-a"), dao.recoverableBundleKeysForOwner("rider-a"))
        assertEquals(listOf("bundle-b"), dao.recoverableBundleKeysForOwner("rider-b"))
    }

    @Test fun newAutomaticBundlePersistsOneOwnerAndOneStableBundleKey() = runBlocking {
        val db = inMemoryDatabase()
        val repository = RoomOfflineQueueRepository(
            queueDao = db.offlineQueueDao(),
            errorDao = db.syncErrorDao(),
            crypto = AesGcmOfflineQueueCrypto(
                keyVersion = 1,
                secretKeyProvider = { SecretKeySpec(ByteArray(32) { 1 }, "AES") }
            ),
            serializer = OfflineQueueSerializer(),
            clock = WallClock { 1_000L },
            config = OfflineQueueConfig(),
            dispatcher = Dispatchers.Unconfined,
            currentRiderOwnerId = { "rider-a" }
        )
        val incident = automaticIncident()
        val request = automaticRequest(incident)

        assertTrue(repository.enqueueIncidentBundle(incident, request).isPersisted)
        assertTrue(repository.enqueueIncidentBundle(incident, request).isPersisted)

        val rows = db.offlineQueueDao().bundleForOwner("rider-a", CLIENT_INCIDENT_ID)
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.ownerUserId == "rider-a" && it.bundleKey == CLIENT_INCIDENT_ID })
        val restored = repository.recoverableIncidentBundleForCurrentRider(CLIENT_INCIDENT_ID)
        assertEquals(CLIENT_INCIDENT_ID, restored?.bundleKey)
        assertEquals("rider-a", restored?.ownerUserId)
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

    private fun automaticIncident() = LocalIncident(
        incidentId = 101L,
        sessionId = 102L,
        assessmentId = 103L,
        windowId = 104L,
        createdAtElapsedRealtimeNanos = 105L,
        cause = IncidentCause.Timeout,
        score = 70,
        riskLevel = RiskLevel.High,
        confidence = 0.9,
        relevantOutcomes = emptyList(),
        ruleSetVersion = "rules",
        validationPolicyVersion = "policy",
        gpsQuality = GpsQualityStatus.Good,
        clientIncidentId = CLIENT_INCIDENT_ID,
        detectedAtEpochMillis = 1_725_000_123_456L,
        latitude = 19.4326,
        longitude = -99.1332,
        remoteTripId = "trip-1"
    )

    private fun automaticRequest(incident: LocalIncident) = AlertDispatchRequest(
        requestId = 106L,
        incidentId = incident.incidentId,
        sessionId = incident.sessionId,
        assessmentId = incident.assessmentId,
        priority = AlertPriority.High,
        reason = incident.cause,
        createdAtElapsedRealtimeNanos = 107L,
        score = incident.score,
        confidence = incident.confidence,
        payload = AlertPayloadSummary(
            incident.sessionId,
            incident.assessmentId,
            incident.incidentId,
            incident.score,
            incident.riskLevel,
            incident.cause,
            "policy"
        ),
        clientAlertRequestId = CLIENT_ALERT_ID
    )

    private companion object {
        const val CLIENT_INCIDENT_ID = "11111111-1111-1111-1111-111111111111"
        const val CLIENT_ALERT_ID = "22222222-2222-2222-2222-222222222222"
    }
}
