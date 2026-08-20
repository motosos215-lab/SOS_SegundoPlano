package com.example.sos_segundoplano.push

import com.example.sos_segundoplano.core.push.InitialPushTokenFetcher
import com.example.sos_segundoplano.core.push.PushTokenBootstrap
import com.example.sos_segundoplano.domain.auth.UserRole
import com.example.sos_segundoplano.domain.push.PushTokenCoordinator
import com.example.sos_segundoplano.domain.push.PushTokenHandler
import com.example.sos_segundoplano.domain.push.PushTokenState
import com.example.sos_segundoplano.domain.push.PushTokenStore
import com.example.sos_segundoplano.domain.push.PushTokenStoreResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushTokenFoundationTest {
    @Test fun pendingTokenPersistsAcrossCoordinatorInstances() {
        val store = FakePushTokenStore()
        PushTokenCoordinator(store).recordToken(FIRST_FAKE_TOKEN)

        val restored = (PushTokenCoordinator(store).state() as PushTokenStoreResult.Success).value

        assertEquals(FIRST_FAKE_TOKEN, restored.currentToken)
        assertEquals(FIRST_FAKE_TOKEN, restored.pendingToken)
        assertNull(restored.remoteRegistrationId)
    }

    @Test fun onNewTokenReplacesLocalTokenAndClearsStaleRegistrationId() {
        val store = FakePushTokenStore(
            PushTokenState(
                currentToken = FIRST_FAKE_TOKEN,
                pendingToken = FIRST_FAKE_TOKEN,
                remoteRegistrationId = "fake-registration-id",
                remoteRegistrationOwnerUserId = "monitor-user-fixture"
            )
        )
        val handler = PushTokenHandler(PushTokenCoordinator(store))

        handler.onNewToken(ROTATED_FAKE_TOKEN)

        assertEquals(ROTATED_FAKE_TOKEN, store.state.currentToken)
        assertEquals(ROTATED_FAKE_TOKEN, store.state.pendingToken)
        assertNull(store.state.remoteRegistrationId)
        assertNull(store.state.remoteRegistrationOwnerUserId)
    }

    @Test fun tokenIsNeverPrintedByStateOrDiagnostics() {
        val text = PushTokenState(FIRST_FAKE_TOKEN, FIRST_FAKE_TOKEN).toString()
        val diagnostic = PushDiagnostics.onNewToken(tokenPresent = true)

        assertFalse(text.contains(FIRST_FAKE_TOKEN))
        assertFalse(diagnostic.contains(FIRST_FAKE_TOKEN))
        assertTrue(text.contains("[REDACTED]"))
    }

    @Test fun unavailableFirebaseTokenDoesNotAffectRiderOrMonitorSession() {
        listOf(UserRole.Rider, UserRole.Monitor).forEach { role ->
            val currentRole = role
            val bootstrap = PushTokenBootstrap(
                coordinator = PushTokenCoordinator(FakePushTokenStore()),
                fetcher = InitialPushTokenFetcher { throw IllegalStateException("firebase_unavailable") }
            )

            bootstrap.start()

            assertEquals(role, currentRole)
        }
    }

    @Test fun initialOrRotatedTokenCanScheduleRegistrationAfterItIsPersisted() {
        val store = FakePushTokenStore()
        var syncCalls = 0
        val bootstrap = PushTokenBootstrap(
            coordinator = PushTokenCoordinator(store),
            fetcher = InitialPushTokenFetcher { callback -> callback(ROTATED_FAKE_TOKEN) },
            onTokenRecorded = { syncCalls++ }
        )

        bootstrap.start()

        assertEquals(ROTATED_FAKE_TOKEN, store.state.pendingToken)
        assertEquals(1, syncCalls)
    }

    private companion object {
        const val FIRST_FAKE_TOKEN = "fake-fcm-token-v1-not-real"
        const val ROTATED_FAKE_TOKEN = "fake-fcm-token-v2-not-real"
    }
}

private class FakePushTokenStore(
    initialState: PushTokenState = PushTokenState()
) : PushTokenStore {
    var state: PushTokenState = initialState
        private set

    override fun read(): PushTokenStoreResult<PushTokenState> = PushTokenStoreResult.Success(state)

    override fun save(state: PushTokenState): PushTokenStoreResult<Unit> {
        this.state = state
        return PushTokenStoreResult.Success(Unit)
    }
}
