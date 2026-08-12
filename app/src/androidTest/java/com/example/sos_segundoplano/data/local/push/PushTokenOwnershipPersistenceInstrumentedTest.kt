package com.example.sos_segundoplano.data.local.push

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.sos_segundoplano.domain.push.PushTokenState
import com.example.sos_segundoplano.domain.push.PushTokenStoreResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PushTokenOwnershipPersistenceInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun setUp() = clearPreferences()

    @After fun tearDown() = clearPreferences()

    @Test fun registrationOwnerSurvivesStoreRecreationWithoutExposingToken() {
        val first = KeystoreEncryptedPushTokenStore(context)
        val expected = PushTokenState(
            currentToken = "fake-fcm-token-not-real",
            pendingToken = null,
            remoteRegistrationId = "registration-fixture",
            remoteRegistrationOwnerUserId = "monitor-user-fixture"
        )

        assertEquals(PushTokenStoreResult.Success(Unit), first.save(expected))

        val restored = KeystoreEncryptedPushTokenStore(context).read()
        assertEquals(PushTokenStoreResult.Success(expected), restored)
    }

    private fun clearPreferences() {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "motosos_push_token_store"
    }
}
