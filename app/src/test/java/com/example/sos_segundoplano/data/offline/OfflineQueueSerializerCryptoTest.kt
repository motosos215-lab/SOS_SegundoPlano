package com.example.sos_segundoplano.data.offline

import com.example.sos_segundoplano.domain.offline.MinorEventSyncPayload
import com.example.sos_segundoplano.domain.offline.LocalIncidentSyncPayload
import com.example.sos_segundoplano.domain.offline.AlertDispatchRequestSyncPayload
import com.example.sos_segundoplano.domain.offline.ConnectivitySyncSnapshot
import com.example.sos_segundoplano.domain.offline.OfflineEventType
import com.example.sos_segundoplano.domain.offline.OfflineSyncErrorCategory
import com.example.sos_segundoplano.domain.offline.OfflineSyncPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

class OfflineQueueSerializerCryptoTest {
    private val serializer = OfflineQueueSerializer()

    @Test fun serializerPreservesExactTripSessionIdentityAndLegacyNull() {
        val withIdentity = OfflineSyncPayload.LocalIncidentPayload(
            LocalIncidentSyncPayload(1L, 2L, 3L, 4L, "Timeout", 1, "High", .9, "rules", "policy", "Good", 10L, 11L,
                clientIncidentId = "incident-A", remoteTripId = "trip-A", tripSessionKey = "trip-session-A")
        )
        val withoutIdentity = withIdentity.copy(payload = withIdentity.payload.copy(tripSessionKey = null))
        val decodedA = (serializer.deserialize(serializer.serialize(withIdentity)) as OfflineSyncPayload.LocalIncidentPayload).payload
        val decodedLegacy = (serializer.deserialize(serializer.serialize(withoutIdentity)) as OfflineSyncPayload.LocalIncidentPayload).payload
        assertEquals("trip-session-A", decodedA.tripSessionKey)
        assertEquals("incident-A", decodedA.clientIncidentId)
        assertEquals("trip-A", decodedA.remoteTripId)
        assertNull(decodedLegacy.tripSessionKey)
    }


    @Test fun serializerRoundTripPreservesConnectivityCapturedAtEnqueue() {
        val connectivity = ConnectivitySyncSnapshot(
            connected = true,
            validated = true,
            metered = true,
            transport = "Cellular",
            timestampMillis = 1_725_000_999_000L
        )
        val payload = OfflineSyncPayload.MinorEventPayload(
            MinorEventSyncPayload(1L, 2L, 3L, 4L, "Bump", 10, 0.8, "policy-v1", 1_000L, 9_000L, connectivity)
        )

        val decoded = serializer.deserialize(serializer.serialize(payload)) as OfflineSyncPayload.MinorEventPayload

        assertEquals(connectivity, decoded.payload.connectivity)
    }

    @Test fun serializerRoundTripPreservesMinorEventPayloadWithoutAndroidObjects() {
        val payload = OfflineSyncPayload.MinorEventPayload(
            MinorEventSyncPayload(1L, 2L, 3L, 4L, "Bump", null, 0.8, "policy=1&special", 1_000L, 9_000L)
        )

        val decoded = serializer.deserialize(serializer.serialize(payload)) as OfflineSyncPayload.MinorEventPayload

        assertEquals(payload.payload, decoded.payload)
    }

    @Test fun serializerRoundTripPreservesIncidentAndAlertRequestPayloads() {
        val incident = OfflineSyncPayload.LocalIncidentPayload(
            LocalIncidentSyncPayload(1L, 2L, 3L, 4L, "Timeout", null, "High", 0.9, "rules?x=1", "policy", "Good", 1_000L, 2_000L)
        )
        val request = OfflineSyncPayload.AlertDispatchRequestPayload(
            AlertDispatchRequestSyncPayload(5L, 1L, 2L, 3L, "High", "Timeout", null, 0.9, "Pending", "NotStarted", 1_000L, 2_000L)
        )

        assertEquals(incident.payload, (serializer.deserialize(serializer.serialize(incident)) as OfflineSyncPayload.LocalIncidentPayload).payload)
        assertEquals(request.payload, (serializer.deserialize(serializer.serialize(request)) as OfflineSyncPayload.AlertDispatchRequestPayload).payload)
    }

    @Test fun serializerRoundTripPreservesDurableAutomaticIncidentFields() {
        val incident = OfflineSyncPayload.LocalIncidentPayload(
            LocalIncidentSyncPayload(
                incidentId = 11L,
                sessionId = 12L,
                assessmentId = 13L,
                windowId = 14L,
                cause = "Timeout",
                score = 70,
                riskLevel = "High",
                confidence = 0.9,
                ruleSetVersion = "rules-v1",
                validationPolicyVersion = "policy-v1",
                gpsQuality = "Good",
                occurredAtEpochMillis = 1_725_000_111_000L,
                createdAtElapsedRealtimeNanos = 2_000L,
                clientIncidentId = "11111111-1111-1111-1111-111111111111",
                detectedAtEpochMillis = 1_725_000_123_456L,
                latitude = 19.4326,
                longitude = -99.1332,
                remoteTripId = "trip-automatic-1"
            )
        )
        val request = OfflineSyncPayload.AlertDispatchRequestPayload(
            AlertDispatchRequestSyncPayload(
                requestId = 15L,
                incidentId = 11L,
                sessionId = 12L,
                assessmentId = 13L,
                priority = "High",
                reason = "Timeout",
                score = 70,
                confidence = 0.9,
                deliveryStatus = "Pending",
                retryState = "NotStarted",
                occurredAtEpochMillis = 1_725_000_111_000L,
                createdAtElapsedRealtimeNanos = 2_000L,
                clientAlertRequestId = "22222222-2222-2222-2222-222222222222"
            )
        )

        val decodedIncident = (serializer.deserialize(serializer.serialize(incident)) as OfflineSyncPayload.LocalIncidentPayload).payload
        val decodedRequest = (serializer.deserialize(serializer.serialize(request)) as OfflineSyncPayload.AlertDispatchRequestPayload).payload

        assertEquals(incident.payload.incidentId, decodedIncident.incidentId)
        assertEquals(incident.payload.sessionId, decodedIncident.sessionId)
        assertEquals(incident.payload.assessmentId, decodedIncident.assessmentId)
        assertEquals(incident.payload.cause, decodedIncident.cause)
        assertEquals(incident.payload.clientIncidentId, decodedIncident.clientIncidentId)
        assertEquals(incident.payload.detectedAtEpochMillis, decodedIncident.detectedAtEpochMillis)
        assertEquals(incident.payload.latitude, decodedIncident.latitude)
        assertEquals(incident.payload.longitude, decodedIncident.longitude)
        assertEquals(incident.payload.remoteTripId, decodedIncident.remoteTripId)
        assertEquals(request.payload.requestId, decodedRequest.requestId)
        assertEquals(request.payload.sessionId, decodedRequest.sessionId)
        assertEquals(request.payload.assessmentId, decodedRequest.assessmentId)
        assertEquals(request.payload.reason, decodedRequest.reason)
        assertEquals(request.payload.clientAlertRequestId, decodedRequest.clientAlertRequestId)
    }

    @Test fun serializerDeserializesLegacyPayloadsWithoutFabricatingDurableFields() {
        val legacyIncident = """
            eventType=local-incident
            schemaVersion=1
            incidentId=11
            sessionId=12
            assessmentId=13
            windowId=14
            cause=Timeout
            score=70
            riskLevel=High
            confidence=0.9
            ruleSetVersion=rules-v1
            validationPolicyVersion=policy-v1
            gpsQuality=Good
            occurredAtEpochMillis=1725000111000
            createdAtElapsedRealtimeNanos=2000
        """.trimIndent().toByteArray()
        val legacyRequest = """
            eventType=alert-dispatch-request
            schemaVersion=1
            requestId=15
            incidentId=11
            sessionId=12
            assessmentId=13
            priority=High
            reason=Timeout
            score=70
            confidence=0.9
            deliveryStatus=Pending
            retryState=NotStarted
            occurredAtEpochMillis=1725000111000
            createdAtElapsedRealtimeNanos=2000
        """.trimIndent().toByteArray()

        val incident = (serializer.deserialize(legacyIncident) as OfflineSyncPayload.LocalIncidentPayload).payload
        val request = (serializer.deserialize(legacyRequest) as OfflineSyncPayload.AlertDispatchRequestPayload).payload

        assertNull(incident.clientIncidentId)
        assertNull(incident.detectedAtEpochMillis)
        assertNull(incident.latitude)
        assertNull(incident.longitude)
        assertNull(incident.remoteTripId)
        assertNull(request.clientAlertRequestId)
    }

    @Test fun serializerPreservesAbsentAndPartialLocationsWithoutFabricatingCoordinates() {
        val payloads = listOf(
            LocalIncidentSyncPayload(1L, 2L, 3L, 4L, "Timeout", null, "High", 0.9, "rules", "policy", "Good", 1_000L, 2_000L, latitude = null, longitude = null),
            LocalIncidentSyncPayload(5L, 2L, 3L, 4L, "Timeout", null, "High", 0.9, "rules", "policy", "Good", 1_000L, 2_000L, latitude = 19.4326, longitude = null),
            LocalIncidentSyncPayload(6L, 2L, 3L, 4L, "Timeout", null, "High", 0.9, "rules", "policy", "Good", 1_000L, 2_000L, latitude = null, longitude = -99.1332)
        )

        payloads.forEach { payload ->
            val decoded = (serializer.deserialize(serializer.serialize(OfflineSyncPayload.LocalIncidentPayload(payload))) as OfflineSyncPayload.LocalIncidentPayload).payload
            assertEquals(payload.latitude, decoded.latitude)
            assertEquals(payload.longitude, decoded.longitude)
        }
    }

    @Test fun serializerReturnsNullForCorruptOrUnknownPayloads() {
        assertNull(serializer.deserialize("eventType=unknown\nschemaVersion=1".toByteArray()))
        assertNull(serializer.deserialize("eventType=minor-event\nschemaVersion=99".toByteArray()))
        assertNull(serializer.deserialize("eventType=minor-event\nschemaVersion=1\neventId=abc".toByteArray()))
        assertNull(serializer.deserialize("eventType=%ZZ\nschemaVersion=1".toByteArray()))
        assertNull(serializer.deserialize("eventType=minor-event\nschemaVersion=1\neventId=1".toByteArray()))
        assertNull(serializer.deserialize("eventType=minor-event\nschemaVersion=1\neventId=1\nsessionId=1\nassessmentId=1\nwindowId=1\ntype=Unknown\nconfidence=nope".toByteArray()))
    }

    @Test fun aesGcmRoundTripUsesDifferentCiphertextAndAuthenticatesAad() {
        val crypto = testCrypto()
        val aad = OfflineQueueAssociatedData("minor-event:1:v1", OfflineEventType.MinorEvent.wireName, 1, 1)
        val plaintext = "session=7&secret=not-logged".toByteArray()

        val first = (crypto.encrypt(plaintext, aad) as OfflineCryptoResult.Success).value
        val second = (crypto.encrypt(plaintext, aad) as OfflineCryptoResult.Success).value
        val decrypted = crypto.decrypt(first.ciphertext, first.nonce, aad) as OfflineCryptoResult.Success

        assertFalse(first.ciphertext.contentEquals(second.ciphertext))
        assertFalse(String(first.ciphertext, Charsets.ISO_8859_1).contains("secret"))
        assertTrue(plaintext.contentEquals(decrypted.value))
    }

    @Test fun aesGcmRejectsTamperedPayloadWrongAadAndWrongKeyVersion() {
        val crypto = testCrypto()
        val aad = OfflineQueueAssociatedData("minor-event:1:v1", OfflineEventType.MinorEvent.wireName, 1, 1)
        val encrypted = (crypto.encrypt("payload".toByteArray(), aad) as OfflineCryptoResult.Success).value
        val tampered = encrypted.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }

        val tamperedResult = crypto.decrypt(tampered, encrypted.nonce, aad) as OfflineCryptoResult.Failure
        val wrongAad = crypto.decrypt(encrypted.ciphertext, encrypted.nonce, aad.copy(idempotencyKey = "minor-event:2:v1")) as OfflineCryptoResult.Failure
        val wrongVersion = crypto.decrypt(encrypted.ciphertext, encrypted.nonce, aad.copy(encryptionKeyVersion = 2)) as OfflineCryptoResult.Failure

        assertEquals(OfflineSyncErrorCategory.Encryption, tamperedResult.category)
        assertEquals("payload_authentication_failed", tamperedResult.sanitizedMessage)
        assertEquals("payload_authentication_failed", wrongAad.sanitizedMessage)
        assertEquals("encryption_key_version_unknown", wrongVersion.sanitizedMessage)
    }

    private fun testCrypto(): AesGcmOfflineQueueCrypto = AesGcmOfflineQueueCrypto(
        keyVersion = 1,
        secretKeyProvider = { SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES") }
    )
}
