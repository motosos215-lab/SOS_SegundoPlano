package com.example.sos_segundoplano.data.local.push

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.sos_segundoplano.domain.push.PushTokenState
import com.example.sos_segundoplano.domain.push.PushTokenStore
import com.example.sos_segundoplano.domain.push.PushTokenStoreResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeystoreEncryptedPushTokenStore(context: Context) : PushTokenStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    override fun read(): PushTokenStoreResult<PushTokenState> = synchronized(lock) {
        val encodedCiphertext = preferences.getString(KEY_CIPHERTEXT, null)
        val encodedNonce = preferences.getString(KEY_NONCE, null)
        if (encodedCiphertext == null && encodedNonce == null) {
            return@synchronized PushTokenStoreResult.Success(PushTokenState())
        }
        if (encodedCiphertext == null || encodedNonce == null) return@synchronized corruptAndClear()
        try {
            val nonce = Base64.decode(encodedNonce, Base64.NO_WRAP)
            if (nonce.size != NONCE_SIZE_BYTES) return@synchronized corruptAndClear()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_SIZE_BITS, nonce))
            cipher.updateAAD(ASSOCIATED_DATA)
            val plaintext = cipher.doFinal(Base64.decode(encodedCiphertext, Base64.NO_WRAP))
            PushTokenStoreResult.Success(decode(plaintext))
        } catch (_: AEADBadTagException) {
            corruptAndClear()
        } catch (_: IllegalArgumentException) {
            corruptAndClear()
        } catch (_: IOException) {
            corruptAndClear()
        } catch (_: GeneralSecurityException) {
            PushTokenStoreResult.Failure
        } catch (_: ProviderException) {
            PushTokenStoreResult.Failure
        }
    }

    override fun save(state: PushTokenState): PushTokenStoreResult<Unit> = synchronized(lock) {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            cipher.updateAAD(ASSOCIATED_DATA)
            val committed = preferences.edit()
                .putString(KEY_CIPHERTEXT, Base64.encodeToString(cipher.doFinal(encode(state)), Base64.NO_WRAP))
                .putString(KEY_NONCE, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .commit()
            if (committed) PushTokenStoreResult.Success(Unit) else PushTokenStoreResult.Failure
        } catch (_: IllegalArgumentException) {
            PushTokenStoreResult.Failure
        } catch (_: GeneralSecurityException) {
            PushTokenStoreResult.Failure
        } catch (_: ProviderException) {
            PushTokenStoreResult.Failure
        }
    }

    private fun corruptAndClear(): PushTokenStoreResult.Failure {
        preferences.edit().clear().commit()
        return PushTokenStoreResult.Failure
    }

    private fun encode(state: PushTokenState): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(FORMAT_VERSION)
            output.writeNullableUtf(state.currentToken)
            output.writeNullableUtf(state.pendingToken)
            output.writeNullableUtf(state.remoteRegistrationId)
            output.writeNullableUtf(state.remoteRegistrationOwnerUserId)
        }
        bytes.toByteArray()
    }

    private fun decode(bytes: ByteArray): PushTokenState = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        val version = input.readInt()
        require(version == LEGACY_FORMAT_VERSION || version == FORMAT_VERSION)
        PushTokenState(
            currentToken = input.readNullableUtf(),
            pendingToken = input.readNullableUtf(),
            remoteRegistrationId = input.readNullableUtf(),
            remoteRegistrationOwnerUserId = if (version >= FORMAT_VERSION) input.readNullableUtf() else null
        )
    }

    private fun DataOutputStream.writeNullableUtf(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeUTF(value)
    }

    private fun DataInputStream.readNullableUtf(): String? = if (readBoolean()) readUTF() else null

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFERENCES_NAME = "motosos_push_token_store"
        const val KEY_CIPHERTEXT = "encrypted_state"
        const val KEY_NONCE = "nonce"
        const val KEY_ALIAS = "motosos_push_token_aes_gcm_v1"
        const val LEGACY_FORMAT_VERSION = 1
        const val FORMAT_VERSION = 2
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_SIZE_BITS = 128
        const val NONCE_SIZE_BYTES = 12
        val ASSOCIATED_DATA = "motosos|push-token-store|v1".toByteArray(Charsets.UTF_8)
    }
}
