package com.example.sos_segundoplano.ui

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.example.sos_segundoplano.core.background.MonitoringServiceStartResult
import com.example.sos_segundoplano.core.background.MonitoringServiceStarter
import com.example.sos_segundoplano.data.remote.trip.ActiveTripLookupResult
import com.example.sos_segundoplano.data.remote.trip.ActiveTripRemoteResolver
import com.example.sos_segundoplano.data.remote.trip.InMemoryRemoteTripSessionStore
import com.example.sos_segundoplano.data.trip.InMemoryTripSessionStore
import com.example.sos_segundoplano.data.trip.MonitoringRecoveryReadiness
import com.example.sos_segundoplano.data.trip.MonitoringRecoveryReadinessProvider
import com.example.sos_segundoplano.data.trip.TripProcessRecoveryCoordinator
import com.example.sos_segundoplano.data.trip.TripProcessRecoveryState
import com.example.sos_segundoplano.domain.auth.AccessToken
import com.example.sos_segundoplano.domain.auth.AuthResult
import com.example.sos_segundoplano.domain.auth.AuthUser
import com.example.sos_segundoplano.domain.auth.SessionState
import com.example.sos_segundoplano.domain.auth.UserRole
import com.example.sos_segundoplano.domain.repository.AuthRepository
import com.example.sos_segundoplano.domain.trip.TripTimingState
import com.example.sos_segundoplano.domain.trip.TripTimingStore
import com.example.sos_segundoplano.features.trip.RiderTripRecoveryGate
import com.example.sos_segundoplano.ui.theme.SOS_SegundoPlanoTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class TripProcessRecoveryUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun riderDoesNotSeeIdleContentBeforeActiveTripRecoveryCompletes() {
        val result = CompletableDeferred<ActiveTripLookupResult>()
        val recoveredContentCompositions = AtomicInteger(0)
        val coordinator = TripProcessRecoveryCoordinator(
            activeTripResolver = ActiveTripRemoteResolver { result.await() },
            remoteTripStore = InMemoryRemoteTripSessionStore(),
            tripSessionStore = InMemoryTripSessionStore(),
            tripTimingStore = FakeTimingStore(),
            readinessProvider = MonitoringRecoveryReadinessProvider { MonitoringRecoveryReadiness.Ready },
            monitoringServiceStarter = MonitoringServiceStarter { MonitoringServiceStartResult.Started }
        )

        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                RiderTripRecoveryGate(FakeRiderAuthRepository(), coordinator) {
                    recoveredContentCompositions.incrementAndGet()
                    Text("recovered rider", Modifier.testTag("recovered_rider_content"))
                }
            }
        }

        composeRule.onNodeWithTag("trip_recovery_progress").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(0, recoveredContentCompositions.get())
        }

        result.complete(ActiveTripLookupResult.Found("remote-trip-1"))

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            coordinator.states.value is TripProcessRecoveryState.Active
        }
        composeRule.onNodeWithTag("recovered_rider_content").assertIsDisplayed()
        composeRule.runOnIdle {
            assertTrue(recoveredContentCompositions.get() > 0)
        }
    }

    private class FakeRiderAuthRepository : AuthRepository {
        private val session = MutableStateFlow<SessionState>(
            SessionState.Authenticated(
                user = AuthUser("rider-1", "rider@example.com", "Rider", "", UserRole.Rider, true),
                accessTokenExpiresAt = java.time.Instant.MAX,
                rememberMe = true,
                generation = 3L
            )
        )
        override suspend fun login(email: String, password: String, rememberMe: Boolean): AuthResult<AuthUser> = error("unused")
        override suspend fun restoreSession(): AuthResult<AuthUser?> = error("unused")
        override suspend fun ensureValidAccessToken(): AuthResult<AccessToken> = error("unused")
        override suspend fun refreshSession(): AuthResult<AuthUser> = error("unused")
        override suspend fun logout(): AuthResult<Unit> = error("unused")
        override fun observeSession(): StateFlow<SessionState> = session
    }

    private class FakeTimingStore : TripTimingStore {
        private val mutableStates = MutableStateFlow<TripTimingState>(TripTimingState.Unknown)
        override val states: StateFlow<TripTimingState> = mutableStates
        override fun beginConfirmedTrip() {
            mutableStates.value = TripTimingState.Active(1_000L)
        }
        override fun clear() {
            mutableStates.value = TripTimingState.Unknown
        }
    }
}
