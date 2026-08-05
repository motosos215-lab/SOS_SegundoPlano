package com.example.sos_segundoplano.data.local.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.sos_segundoplano.domain.auth.AuthSession
import com.example.sos_segundoplano.domain.auth.AuthUser
import com.example.sos_segundoplano.domain.auth.SessionSecrets
import com.example.sos_segundoplano.domain.auth.SessionStorageFailureReason
import com.example.sos_segundoplano.domain.auth.SessionStore
import com.example.sos_segundoplano.domain.auth.SessionStoreResult
import com.example.sos_segundoplano.domain.auth.UserRole
import com.squareup.moshi.JsonClass
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.time.DateTimeException
import java.time.Instant
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeystoreEncryptedSessionStore(
    context: Context,
    moshi: Moshi,
    private val keyAlias: String = PRODUCTION_KEY_ALIAS,
    private val preferencesName: String = PREFERENCES_NAME,
    aad: String = context.packageName + AAD_SUFFIX,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : SessionStore {
    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val associatedData = aad.toByteArray(Charsets.UTF_8)
    private val adapter = moshi.adapter(PersistedAuthSessionDto::class.java)
    private val storageMutex = Mutex()

    override suspend fun save(session: AuthSession): SessionStoreResult<Unit> = withContext(dispatcher) {
        storageMutex.withLock {
            if (!session.rememberMe) return@withLock clearInternal()
            try {
                val plaintext = adapter.toJson(session.toPersistedDto()).toByteArray(Charsets.UTF_8)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
                cipher.updateAAD(associatedData)
                val ciphertext = cipher.doFinal(plaintext)
                val committed = preferences.edit()
                    .putString(KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                    .putString(KEY_NONCE, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                    .putInt(KEY_VERSION, KEY_VERSION_VALUE)
                    .commit()
                if (committed) {
                    SessionStoreResult.Success(Unit)
                } else {
                    clearInternal()
                    SessionStoreResult.Failure(SessionStorageFailureReason.Unavailable)
                }
            } catch (_: JsonDataException) {
                clearInternal()
                SessionStoreResult.Failure(SessionStorageFailureReason.Serialization)
            } catch (_: IllegalArgumentException) {
                clearInternal()
                SessionStoreResult.Failure(SessionStorageFailureReason.Serialization)
            } catch (_: GeneralSecurityException) {
                clearInternal()
                SessionStoreResult.Failure(SessionStorageFailureReason.Unavailable)
            } catch (_: ProviderException) {
                clearInternal()
                SessionStoreResult.Failure(SessionStorageFailureReason.Unavailable)
            }
        }
    }

    override suspend fun read(): SessionStoreResult<AuthSession> = withContext(dispatcher) {
        storageMutex.withLock {
            val encodedCiphertext = preferences.getString(KEY_CIPHERTEXT, null)
            val encodedNonce = preferences.getString(KEY_NONCE, null)
            val storedVersion = if (preferences.contains(KEY_VERSION)) preferences.getInt(KEY_VERSION, 0) else null
            if (encodedCiphertext == null && encodedNonce == null && storedVersion == null) {
                return@withLock SessionStoreResult.Empty
            }
            if (encodedCiphertext == null || encodedNonce == null || storedVersion != KEY_VERSION_VALUE) {
                clearInternal()
                return@withLock SessionStoreResult.Failure(SessionStorageFailureReason.Corrupt)
            }
            try {
                val ciphertext = Base64.decode(encodedCiphertext, Base64.NO_WRAP)
                val nonce = Base64.decode(encodedNonce, Base64.NO_WRAP)
                if (ciphertext.isEmpty() || nonce.size != NONCE_SIZE_BYTES) {
                    clearInternal()
                    return@withLock SessionStoreResult.Failure(SessionStorageFailureReason.Corrupt)
                }
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, getExistingKey(), GCMParameterSpec(TAG_SIZE_BITS, nonce))
                cipher.updateAAD(associatedData)
                val plaintext = cipher.doFinal(ciphertext)
                val persisted = adapter.fromJson(plaintext.toString(Charsets.UTF_8))
                    ?: return@withLock corruptAndClear(SessionStorageFailureReason.Serialization)
                val session = persisted.toDomain()
                    ?: return@withLock corruptAndClear(SessionStorageFailureReason.Serialization)
                SessionStoreResult.Success(session)
            } catch (_: AEADBadTagException) {
                corruptAndClear(SessionStorageFailureReason.Tampered)
            } catch (_: JsonDataException) {
                corruptAndClear(SessionStorageFailureReason.Serialization)
            } catch (_: DateTimeException) {
                corruptAndClear(SessionStorageFailureReason.Serialization)
            } catch (_: IllegalArgumentException) {
                corruptAndClear(SessionStorageFailureReason.Corrupt)
            } catch (_: GeneralSecurityException) {
                corruptAndClear(SessionStorageFailureReason.Unavailable)
            } catch (_: ProviderException) {
                corruptAndClear(SessionStorageFailureReason.Unavailable)
            }
        }
    }

    override suspend fun clear(): SessionStoreResult<Unit> = withContext(dispatcher) {
        storageMutex.withLock { clearInternal() }
    }

    private fun clearInternal(): SessionStoreResult<Unit> =
        if (preferences.edit().clear().commit()) {
            SessionStoreResult.Success(Unit)
        } else {
            SessionStoreResult.Failure(SessionStorageFailureReason.Unavailable)
        }

    private fun corruptAndClear(reason: SessionStorageFailureReason): SessionStoreResult.Failure {
        clearInternal()
        return SessionStoreResult.Failure(reason)
    }

    private fun getExistingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val entry = keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry
            ?: throw GeneralSecurityException("session_key_unavailable")
        return entry.secretKey
    }

    private fun getOrCreateKey(): SecretKey = try {
        getExistingKey()
    } catch (_: GeneralSecurityException) {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        generator.generateKey()
    }

    private fun AuthSession.toPersistedDto(): PersistedAuthSessionDto = PersistedAuthSessionDto(
        schemaVersion = SESSION_SCHEMA_VERSION,
        accessToken = secrets.accessToken(),
        refreshToken = secrets.refreshToken(),
        accessTokenExpiresAt = accessTokenExpiresAt.toString(),
        userId = user.id,
        email = user.email,
        fullName = user.fullName,
        phoneNumber = user.phoneNumber,
        role = user.role.name,
        isActive = user.isActive,
        rememberMe = rememberMe
    )

    private fun PersistedAuthSessionDto.toDomain(): AuthSession? {
        if (schemaVersion != SESSION_SCHEMA_VERSION || !rememberMe) return null
        if (accessToken.isBlank() || refreshToken.isBlank()) return null
        return AuthSession(
            secrets = SessionSecrets(accessToken, refreshToken),
            accessTokenExpiresAt = Instant.parse(accessTokenExpiresAt),
            user = AuthUser(
                id = userId,
                email = email,
                fullName = fullName,
                phoneNumber = phoneNumber,
                role = UserRole.fromApiValue(role),
                isActive = isActive
            ),
            rememberMe = true
        )
    }

    companion object {
        const val PRODUCTION_KEY_ALIAS = "motosos_auth_session_aes_gcm_v1"
        const val PREFERENCES_NAME = "motosos_auth_session_secure"
        const val KEY_CIPHERTEXT = "ciphertext"
        const val KEY_NONCE = "nonce"
        const val KEY_VERSION = "key_version"
        const val AAD_SUFFIX = ":auth-session:v1"
        const val KEY_VERSION_VALUE = 1

        private const val SESSION_SCHEMA_VERSION = 1
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_SIZE_BITS = 128
        private const val NONCE_SIZE_BYTES = 12
    }
}

@JsonClass(generateAdapter = false)
private data class PersistedAuthSessionDto(
    val schemaVersion: Int,
    val accessToken: String,
    val refreshToken: String,
    val accessTokenExpiresAt: String,
    val userId: String,
    val email: String,
    val fullName: String,
    val phoneNumber: String,
    val role: String,
    val isActive: Boolean,
    val rememberMe: Boolean
)
