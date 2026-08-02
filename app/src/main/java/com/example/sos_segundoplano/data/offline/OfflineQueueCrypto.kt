package com.example.sos_segundoplano.data.offline

import com.example.sos_segundoplano.domain.offline.OfflineSyncErrorCategory

data class OfflineQueueAssociatedData(
    val idempotencyKey: String,
    val eventType: String,
    val payloadSchemaVersion: Int,
    val encryptionKeyVersion: Int
) {
    fun encode(): ByteArray = listOf(
        idempotencyKey,
        eventType,
        payloadSchemaVersion.toString(),
        encryptionKeyVersion.toString()
    ).joinToString(separator = "|").toByteArray(Charsets.UTF_8)
}

data class EncryptedPayload(
    val ciphertext: ByteArray,
    val nonce: ByteArray,
    val keyVersion: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncryptedPayload) return false
        return ciphertext.contentEquals(other.ciphertext) && nonce.contentEquals(other.nonce) && keyVersion == other.keyVersion
    }

    override fun hashCode(): Int {
        var result = ciphertext.contentHashCode()
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + keyVersion
        return result
    }
}

sealed interface OfflineCryptoResult<out T> {
    data class Success<T>(val value: T) : OfflineCryptoResult<T>
    data class Failure(val category: OfflineSyncErrorCategory, val sanitizedMessage: String) : OfflineCryptoResult<Nothing>
}

interface OfflineQueueCrypto {
    fun encrypt(plaintext: ByteArray, associatedData: OfflineQueueAssociatedData): OfflineCryptoResult<EncryptedPayload>
    fun decrypt(ciphertext: ByteArray, nonce: ByteArray, associatedData: OfflineQueueAssociatedData): OfflineCryptoResult<ByteArray>
}
