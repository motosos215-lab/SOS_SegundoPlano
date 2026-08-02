package com.example.sos_segundoplano.data.offline

import com.example.sos_segundoplano.domain.offline.OfflineSyncErrorCategory
import java.security.GeneralSecurityException
import java.security.ProviderException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

open class AesGcmOfflineQueueCrypto(
    private val keyVersion: Int,
    private val secretKeyProvider: () -> SecretKey
) : OfflineQueueCrypto {
    init {
        require(keyVersion > 0)
    }

    override fun encrypt(plaintext: ByteArray, associatedData: OfflineQueueAssociatedData): OfflineCryptoResult<EncryptedPayload> {
        if (associatedData.encryptionKeyVersion != keyVersion) {
            return OfflineCryptoResult.Failure(OfflineSyncErrorCategory.Encryption, "encryption_key_version_mismatch")
        }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKeyProvider())
            cipher.updateAAD(associatedData.encode())
            OfflineCryptoResult.Success(EncryptedPayload(cipher.doFinal(plaintext), cipher.iv, keyVersion))
        } catch (_: IllegalArgumentException) {
            OfflineCryptoResult.Failure(OfflineSyncErrorCategory.Encryption, "payload_encryption_failed")
        } catch (_: GeneralSecurityException) {
            OfflineCryptoResult.Failure(OfflineSyncErrorCategory.Encryption, "payload_encryption_failed")
        } catch (_: ProviderException) {
            OfflineCryptoResult.Failure(OfflineSyncErrorCategory.Encryption, "payload_encryption_failed")
        }
    }

    override fun decrypt(ciphertext: ByteArray, nonce: ByteArray, associatedData: OfflineQueueAssociatedData): OfflineCryptoResult<ByteArray> {
        if (associatedData.encryptionKeyVersion != keyVersion) {
            return OfflineCryptoResult.Failure(OfflineSyncErrorCategory.Encryption, "encryption_key_version_unknown")
        }
        if (nonce.size != NONCE_SIZE_BYTES) {
            return OfflineCryptoResult.Failure(OfflineSyncErrorCategory.Encryption, "encryption_nonce_invalid")
        }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKeyProvider(), GCMParameterSpec(TAG_SIZE_BITS, nonce))
            cipher.updateAAD(associatedData.encode())
            OfflineCryptoResult.Success(cipher.doFinal(ciphertext))
        } catch (_: AEADBadTagException) {
            OfflineCryptoResult.Failure(OfflineSyncErrorCategory.Encryption, "payload_authentication_failed")
        } catch (_: IllegalArgumentException) {
            OfflineCryptoResult.Failure(OfflineSyncErrorCategory.Encryption, "payload_decryption_failed")
        } catch (_: java.security.GeneralSecurityException) {
            OfflineCryptoResult.Failure(OfflineSyncErrorCategory.Encryption, "payload_decryption_failed")
        }
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_SIZE_BITS = 128
        private const val NONCE_SIZE_BYTES = 12
    }
}
