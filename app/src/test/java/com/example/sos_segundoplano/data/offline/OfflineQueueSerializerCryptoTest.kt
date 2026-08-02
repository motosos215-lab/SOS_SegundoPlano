package com.example.sos_segundoplano.data.offline

import com.example.sos_segundoplano.domain.offline.MinorEventSyncPayload
import com.example.sos_segundoplano.domain.offline.LocalIncidentSyncPayload
import com.example.sos_segundoplano.domain.offline.AlertDispatchRequestSyncPayload
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
