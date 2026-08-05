package com.example.sos_segundoplano.data.local.auth

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.example.sos_segundoplano.R
import com.example.sos_segundoplano.data.remote.auth.AuthNetworkFactory
import com.example.sos_segundoplano.domain.auth.AuthSession
import com.example.sos_segundoplano.domain.auth.AuthUser
import com.example.sos_segundoplano.domain.auth.SessionSecrets
import com.example.sos_segundoplano.domain.auth.SessionStoreResult
import com.example.sos_segundoplano.domain.auth.UserRole
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.xmlpull.v1.XmlPullParser
import java.security.KeyStore
import java.time.Instant
import java.util.UUID

class KeystoreEncryptedSessionStoreInstrumentedTest {
    private lateinit var context: Context
    private lateinit var keyAlias: String
    private lateinit var preferencesName: String

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val unique = UUID.randomUUID().toString()
        keyAlias = "motosos_auth_session_test_$unique"
        preferencesName = "motosos_auth_session_test_$unique"
        deleteKey(keyAlias)
        preferences().edit().clear().commit()
    }

    @After fun tearDown() {
        preferences().edit().clear().commit()
        deleteKey(keyAlias)
    }

    @Test fun encryptedRoundTripRestoresCompleteSession() = runBlocking {
        val store = store()
        val session = session(rememberMe = true)

        assertTrue(store.save(session) is SessionStoreResult.Success)
        val restored = store.read()

        assertTrue(restored is SessionStoreResult.Success)
        val value = (restored as SessionStoreResult.Success).value
        assertEquals(session.user, value.user)
        assertEquals(session.accessTokenExpiresAt, value.accessTokenExpiresAt)
        assertEquals("instrumented-access", value.secrets.accessToken())
        assertEquals("instrumented-refresh", value.secrets.refreshToken())
        assertTrue(value.rememberMe)
    }

    @Test fun preferencesContainOnlyCiphertextNonceAndVersionWithoutPlainTokens() = runBlocking {
        val store = store()
        store.save(session(rememberMe = true))

        val all = preferences().all
        assertEquals(
            setOf(
                KeystoreEncryptedSessionStore.KEY_CIPHERTEXT,
                KeystoreEncryptedSessionStore.KEY_NONCE,
                KeystoreEncryptedSessionStore.KEY_VERSION
            ),
            all.keys
        )
        val persistedText = all.values.joinToString("|")
        assertFalse(persistedText.contains("instrumented-access"))
        assertFalse(persistedText.contains("instrumented-refresh"))
    }

    @Test fun tamperedCiphertextFailsAndClearsStorage() = runBlocking {
        val store = store()
        store.save(session(rememberMe = true))
        mutatePreference(KeystoreEncryptedSessionStore.KEY_CIPHERTEXT)

        assertTrue(store.read() is SessionStoreResult.Failure)
        assertTrue(preferences().all.isEmpty())
    }

    @Test fun tamperedNonceFailsAndClearsStorage() = runBlocking {
        val store = store()
        store.save(session(rememberMe = true))
        mutatePreference(KeystoreEncryptedSessionStore.KEY_NONCE)

        assertTrue(store.read() is SessionStoreResult.Failure)
        assertTrue(preferences().all.isEmpty())
    }

    @Test fun wrongAssociatedDataFailsAuthentication() = runBlocking {
        store(aad = context.packageName + ":auth-session:expected").save(session(rememberMe = true))
        val wrongAadStore = store(aad = context.packageName + ":auth-session:wrong")

        assertTrue(wrongAadStore.read() is SessionStoreResult.Failure)
        assertTrue(preferences().all.isEmpty())
    }

    @Test fun clearRemovesPersistedSession() = runBlocking {
        val store = store()
        store.save(session(rememberMe = true))
        assertTrue(store.clear() is SessionStoreResult.Success)
        assertEquals(SessionStoreResult.Empty, store.read())
    }

    @Test fun rememberMeFalseDoesNotWriteStorage() = runBlocking {
        val store = store()
        assertTrue(store.save(session(rememberMe = false)) is SessionStoreResult.Success)
        assertTrue(preferences().all.isEmpty())
        assertEquals(SessionStoreResult.Empty, store.read())
    }

    @Test fun testAliasIsIsolatedAndGeneratedKeyIsNotExportable() = runBlocking {
        assertTrue(keyAlias.startsWith("motosos_auth_session_test_"))
        assertFalse(keyAlias == KeystoreEncryptedSessionStore.PRODUCTION_KEY_ALIAS)
        store().save(session(rememberMe = true))

        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = keyStore.getKey(keyAlias, null)
        assertTrue(keyStore.containsAlias(keyAlias))
        assertNull(key.encoded)
    }

    @Test fun sessionPreferencesHaveExplicitBackupAndTransferExclusions() {
        assertTrue(xmlContainsSessionExclusion(R.xml.backup_rules))
        assertTrue(xmlContainsSessionExclusion(R.xml.data_extraction_rules))
    }

    private fun store(aad: String = context.packageName + KeystoreEncryptedSessionStore.AAD_SUFFIX) =
        KeystoreEncryptedSessionStore(
            context = context,
            moshi = AuthNetworkFactory.createMoshi(),
            keyAlias = keyAlias,
            preferencesName = preferencesName,
            aad = aad
        )

    private fun session(rememberMe: Boolean): AuthSession = AuthSession(
        secrets = SessionSecrets("instrumented-access", "instrumented-refresh"),
        accessTokenExpiresAt = Instant.parse("2026-08-04T12:00:00Z"),
        user = AuthUser(
            id = "rider-id",
            email = "rider@example.com",
            fullName = "Moto Rider",
            phoneNumber = "+52 555 555 5555",
            role = UserRole.Rider,
            isActive = true
        ),
        rememberMe = rememberMe
    )

    private fun mutatePreference(key: String) {
        val encoded = requireNotNull(preferences().getString(key, null))
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        preferences().edit().putString(key, Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()
    }

    private fun preferences() = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    private fun deleteKey(alias: String) {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    private fun xmlContainsSessionExclusion(resourceId: Int): Boolean {
        val parser = context.resources.getXml(resourceId)
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "exclude") {
                val domain = parser.getAttributeValue(null, "domain")
                val path = parser.getAttributeValue(null, "path")
                if (domain == "sharedpref" && path == KeystoreEncryptedSessionStore.PREFERENCES_NAME + ".xml") {
                    parser.close()
                    return true
                }
            }
            parser.next()
        }
        parser.close()
        return false
    }
}
