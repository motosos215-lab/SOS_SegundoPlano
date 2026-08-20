package com.example.sos_segundoplano.data.wear

import com.example.sos_segundoplano.core.background.MonitoringServiceStartResult
import com.example.sos_segundoplano.core.background.MonitoringServiceStarter
import com.example.sos_segundoplano.core.background.MonitoringServiceStopResult
import com.example.sos_segundoplano.core.background.MonitoringServiceStopper
import com.example.sos_segundoplano.core.permissions.AppNotificationStatus
import com.example.sos_segundoplano.core.permissions.AppNotificationStatusProvider
import com.example.sos_segundoplano.core.permissions.BackgroundLocationPermissionStatus
import com.example.sos_segundoplano.core.permissions.BackgroundLocationPermissionStatusProvider
import com.example.sos_segundoplano.core.permissions.BluetoothRequirementStatus
import com.example.sos_segundoplano.core.permissions.BluetoothRequirementStatusProvider
import com.example.sos_segundoplano.data.remote.trip.InMemoryRemoteTripSessionStore
import com.example.sos_segundoplano.data.remote.trip.FinishTripRequestDto
import com.example.sos_segundoplano.data.remote.trip.RemoteTripIdFinisher
import com.example.sos_segundoplano.data.remote.trip.ResolvedRemoteTripStarter
import com.example.sos_segundoplano.data.remote.trip.TripMutationResult
import com.example.sos_segundoplano.data.trip.InMemoryTripSessionStore
import com.example.sos_segundoplano.domain.auth.AccessToken
import com.example.sos_segundoplano.domain.auth.AuthResult
import com.example.sos_segundoplano.domain.auth.AuthUser
import com.example.sos_segundoplano.domain.auth.SessionState
import com.example.sos_segundoplano.domain.auth.UserRole
import com.example.sos_segundoplano.domain.repository.AuthRepository
import com.example.sos_segundoplano.domain.model.TripSessionState
import com.example.sos_segundoplano.domain.rules.GpsQualityStatus
import com.example.sos_segundoplano.domain.rules.RiskLevel
import com.example.sos_segundoplano.domain.validation.IncidentCause
import com.example.sos_segundoplano.domain.validation.IncidentRemoteCreationStatus
import com.example.sos_segundoplano.domain.validation.LocalIncident
import com.example.sos_segundoplano.wearprotocol.FinishTripActionRequest
import com.example.sos_segundoplano.wearprotocol.ManualSosActionRequest
import com.example.sos_segundoplano.wearprotocol.PhoneActionResult
import com.example.sos_segundoplano.wearprotocol.StartTripActionRequest
import com.example.sos_segundoplano.wearprotocol.TripStateRequest
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WearPhoneActionCoordinatorTest {

    @Test
    fun authenticatedRiderWithoutTripReturnsInactiveState() = runBlocking {
        val response = coordinator(
            session = authenticated(UserRole.Rider),
            clockMillis = 1_723_766_400_000L,
        ).currentTripState(TripStateRequest("request-trip-state-001", 1L))

        assertEquals(PhoneActionResult.OK, response.result)
        assertEquals("request-trip-state-001", response.requestId)
        assertFalse(response.active)
        assertNull(response.remoteTripId)
        assertNull(response.startedAtEpochMs)
        assertEquals(1_723_766_400_000L, response.updatedAtEpochMs)
    }

    @Test
    fun authenticatedRiderWithTripReturnsActiveState() = runBlocking {
        val store = InMemoryRemoteTripSessionStore().apply {
            setRemoteTripId("trip-real-123")
            setStartedAtEpochMs(1_723_766_400_000L)
        }
        val response = coordinator(
            session = authenticated(UserRole.Rider),
            remoteTripStore = store,
        ).currentTripState(TripStateRequest("request-trip-state-active", 1L))

        assertEquals(PhoneActionResult.OK, response.result)
        assertEquals("request-trip-state-active", response.requestId)
        assertTrue(response.active)
        assertEquals("trip-real-123", response.remoteTripId)
        assertEquals(1_723_766_400_000L, response.startedAtEpochMs)
    }

    @Test
    fun repeatedTripStateRefreshReturnsTheSameCanonicalStartedAt() = runBlocking {
        val store = InMemoryRemoteTripSessionStore().apply {
            setRemoteTripId("trip-real-123")
            setStartedAtEpochMs(1_723_766_400_000L)
        }
        val coordinator = coordinator(session = authenticated(UserRole.Rider), remoteTripStore = store)

        val first = coordinator.currentTripState(TripStateRequest("request-trip-state-1", 1L))
        val second = coordinator.currentTripState(TripStateRequest("request-trip-state-2", 2L))

        assertEquals(1_723_766_400_000L, first.startedAtEpochMs)
        assertEquals(first.startedAtEpochMs, second.startedAtEpochMs)
    }

    @Test
    fun loggedOutDoesNotExposeTripState() = runBlocking {
        val store = InMemoryRemoteTripSessionStore().apply { setRemoteTripId("trip-secret-123") }
        val response = coordinator(
            session = SessionState.LoggedOut,
            remoteTripStore = store,
        ).currentTripState(TripStateRequest("request-logged-out", 1L))

        assertEquals(PhoneActionResult.NOT_AUTHENTICATED, response.result)
        assertEquals("request-logged-out", response.requestId)
        assertFalse(response.active)
        assertNull(response.remoteTripId)
    }

    @Test
    fun monitorRoleDoesNotExposeTripState() = runBlocking {
        val store = InMemoryRemoteTripSessionStore().apply { setRemoteTripId("trip-secret-123") }
        val response = coordinator(
            session = authenticated(UserRole.Monitor),
            remoteTripStore = store,
        ).currentTripState(TripStateRequest("request-monitor", 1L))

        assertEquals(PhoneActionResult.WRONG_ROLE, response.result)
        assertEquals("request-monitor", response.requestId)
        assertFalse(response.active)
        assertNull(response.remoteTripId)
    }

    @Test
    fun refreshingRiderCanReadTripState() = runBlocking {
        val store = InMemoryRemoteTripSessionStore().apply { setRemoteTripId("trip-refresh-123") }
        val response = coordinator(
            session = refreshing(UserRole.Rider),
            remoteTripStore = store,
        ).currentTripState(TripStateRequest("request-refresh", 1L))

        assertEquals(PhoneActionResult.OK, response.result)
        assertEquals("request-refresh", response.requestId)
        assertTrue(response.active)
        assertEquals("trip-refresh-123", response.remoteTripId)
        assertNull(response.startedAtEpochMs)
    }

    @Test
    fun refreshingMonitorDoesNotExposeTripState() = runBlocking {
        val store = InMemoryRemoteTripSessionStore().apply { setRemoteTripId("trip-secret-123") }
        val response = coordinator(
            session = refreshing(UserRole.Monitor),
            remoteTripStore = store,
        ).currentTripState(TripStateRequest("request-refresh-monitor", 1L))

        assertEquals(PhoneActionResult.WRONG_ROLE, response.result)
        assertFalse(response.active)
        assertNull(response.remoteTripId)
    }

    @Test
    fun startTripLoggedOutReturnsNotAuthenticatedWithoutSideEffects() = runBlocking {
        val harness = startHarness(session = SessionState.LoggedOut)

        val response = harness.coordinator.startTrip(startRequest("request-start-auth-001", "command-start-auth-001"), RESPONSE_CLOCK)

        assertEquals(PhoneActionResult.NOT_AUTHENTICATED, response.result)
        assertEquals("trip_start_not_authenticated", response.sanitizedCode)
        assertEquals("request-start-auth-001", response.requestId)
        assertEquals(0, harness.remoteStarter.calls)
        assertEquals(0, harness.monitoringStarter.calls)
        assertNull(harness.remoteTripStore.remoteTripId.value)
    }

    @Test
    fun startTripMonitorReturnsWrongRoleWithoutSideEffects() = runBlocking {
        val harness = startHarness(session = authenticated(UserRole.Monitor))

        val response = harness.coordinator.startTrip(startRequest("request-start-role-001", "command-start-role-001"), RESPONSE_CLOCK)

        assertEquals(PhoneActionResult.WRONG_ROLE, response.result)
        assertEquals("trip_start_wrong_role", response.sanitizedCode)
        assertEquals(0, harness.remoteStarter.calls)
        assertEquals(0, harness.monitoringStarter.calls)
        assertNull(harness.remoteTripStore.remoteTripId.value)
    }

    @Test
    fun startTripMissingRequirementReturnsPhoneActionRequiredWithoutSideEffects() = runBlocking {
        val harness = startHarness(locationStatus = BackgroundLocationPermissionStatus.BackgroundMissing)

        val response = harness.coordinator.startTrip(startRequest("request-start-location-001", "command-start-location-001"), RESPONSE_CLOCK)

        assertEquals(PhoneActionResult.PHONE_ACTION_REQUIRED, response.result)
        assertEquals("location_permission_required", response.sanitizedCode)
        assertEquals(0, harness.remoteStarter.calls)
        assertEquals(0, harness.monitoringStarter.calls)
        assertNull(harness.remoteTripStore.remoteTripId.value)
    }

    @Test
    fun startTripCreatesRemoteTripAndStartsMonitoring() = runBlocking {
        val harness = startHarness(remoteTripId = "trip-real-123")
        val request = startRequest("request-start-001", "command-start-777")

        val response = harness.coordinator.startTrip(request, RESPONSE_CLOCK)

        assertEquals(PhoneActionResult.OK, response.result)
        assertEquals(request.requestId, response.requestId)
        assertEquals("trip-real-123", response.remoteTripId)
        assertNull(response.sanitizedCode)
        assertEquals(1, harness.remoteStarter.calls)
        assertEquals(1, harness.monitoringStarter.calls)
        assertEquals(
            WearCommandState.TerminalSuccess,
            harness.commandStore.find(request.commandId, WearCommandAction.StartTrip)?.state,
        )
    }

    @Test
    fun startTripSameCommandIdWithNewRequestIdReusesTerminalResult() = runBlocking {
        val harness = startHarness(remoteTripId = "trip-real-123")
        val first = startRequest("request-start-001", "command-start-777")
        val second = startRequest("request-start-002", "command-start-777")

        harness.coordinator.startTrip(first, RESPONSE_CLOCK)
        val response = harness.coordinator.startTrip(second, RESPONSE_CLOCK + 1L)

        assertEquals(PhoneActionResult.OK, response.result)
        assertEquals("request-start-002", response.requestId)
        assertEquals("trip-real-123", response.remoteTripId)
        assertEquals(1, harness.remoteStarter.calls)
        assertEquals(1, harness.monitoringStarter.calls)
    }

    @Test
    fun startTripWithExistingRemoteTripSkipsRemoteCreation() = runBlocking {
        val existingStore = InMemoryRemoteTripSessionStore().apply { setRemoteTripId("trip-existing-123") }
        val harness = startHarness(remoteTripStore = existingStore, remoteTripId = "trip-should-not-be-created")

        val response = harness.coordinator.startTrip(startRequest("request-existing-001", "command-existing-001"), RESPONSE_CLOCK)

        assertEquals(PhoneActionResult.OK, response.result)
        assertEquals("trip-existing-123", response.remoteTripId)
        assertEquals(0, harness.remoteStarter.calls)
        assertEquals(1, harness.monitoringStarter.calls)
    }

    @Test
    fun startTripMonitoringFailureAfterRemoteCreationPreservesTrip() = runBlocking {
        val harness = startHarness(
            remoteTripId = "trip-partial-123",
            monitoringResult = MonitoringServiceStartResult.Failed,
        )
        val request = startRequest("request-partial-001", "command-partial-001")

        val response = harness.coordinator.startTrip(request, RESPONSE_CLOCK)

        assertEquals(PhoneActionResult.RETRYABLE_ERROR, response.result)
        assertEquals("monitoring_start_retry_required", response.sanitizedCode)
        assertEquals("trip-partial-123", response.remoteTripId)
        assertEquals("trip-partial-123", harness.remoteTripStore.remoteTripId.value)
        assertEquals(WearCommandState.PartialSideEffect, harness.commandStore.find(request.commandId, WearCommandAction.StartTrip)?.state)
        assertEquals(1, harness.remoteStarter.calls)
    }

    @Test
    fun startTripRetryAfterMonitoringFailureDoesNotCreateSecondRemoteTrip() = runBlocking {
        val harness = startHarness(
            remoteTripId = "trip-partial-123",
            monitoringResult = MonitoringServiceStartResult.Failed,
        )
        val first = startRequest("request-partial-001", "command-partial-001")
        val retry = startRequest("request-partial-002", "command-partial-001")

        harness.coordinator.startTrip(first, RESPONSE_CLOCK)
        harness.monitoringStarter.result = MonitoringServiceStartResult.Started
        val response = harness.coordinator.startTrip(retry, RESPONSE_CLOCK + 1L)

        assertEquals(PhoneActionResult.OK, response.result)
        assertEquals("request-partial-002", response.requestId)
        assertEquals("trip-partial-123", response.remoteTripId)
        assertEquals(1, harness.remoteStarter.calls)
        assertEquals(2, harness.monitoringStarter.calls)
        assertEquals(WearCommandState.TerminalSuccess, harness.commandStore.find(retry.commandId, WearCommandAction.StartTrip)?.state)
    }

    @Test
    fun startTripRemoteFailureReturnsRetryableWithoutStartingMonitoring() = runBlocking {
        val harness = startHarness(remoteResult = TripMutationResult.NetworkUnavailable("network_unavailable"))

        val response = harness.coordinator.startTrip(startRequest("request-remote-failure-001", "command-remote-failure-001"), RESPONSE_CLOCK)

        assertEquals(PhoneActionResult.RETRYABLE_ERROR, response.result)
        assertEquals("network_unavailable", response.sanitizedCode)
        assertNull(response.remoteTripId)
        assertEquals(1, harness.remoteStarter.calls)
        assertEquals(0, harness.monitoringStarter.calls)
    }

    @Test
    fun finishTripLoggedOutReturnsNotAuthenticatedWithoutSideEffects() = runBlocking {
        val harness = finishHarness(session = SessionState.LoggedOut)

        val response = harness.coordinator.finishTrip(finishRequest("request-finish-auth-001", "command-finish-auth-001", "trip-real-123"), RESPONSE_CLOCK)

        assertEquals(PhoneActionResult.NOT_AUTHENTICATED, response.result)
        assertEquals("trip_finish_not_authenticated", response.sanitizedCode)
        assertEquals("request-finish-auth-001", response.requestId)
        assertEquals(0, harness.stopper.calls)
        assertEquals(0, harness.finisher.calls)
        assertEquals("trip-real-123", harness.remoteTripStore.remoteTripId.value)
    }

    @Test
    fun finishTripMonitorReturnsWrongRoleWithoutSideEffects() = runBlocking {
        val harness = finishHarness(session = authenticated(UserRole.Monitor))

        val response = harness.coordinator.finishTrip(finishRequest("request-finish-role-001", "command-finish-role-001", "trip-real-123"), RESPONSE_CLOCK)

        assertEquals(PhoneActionResult.WRONG_ROLE, response.result)
        assertEquals("trip_finish_wrong_role", response.sanitizedCode)
        assertEquals(0, harness.stopper.calls)
        assertEquals(0, harness.finisher.calls)
        assertEquals("trip-real-123", harness.remoteTripStore.remoteTripId.value)
    }

    @Test
    fun finishTripWithoutActiveTripReturnsNoActiveTrip() = runBlocking {
        val harness = finishHarness(remoteTripStore = InMemoryRemoteTripSessionStore())

        val response = harness.coordinator.finishTrip(finishRequest("request-finish-none-001", "command-finish-none-001", "trip-stale-123"), RESPONSE_CLOCK)

        assertEquals(PhoneActionResult.NO_ACTIVE_TRIP, response.result)
        assertEquals("no_active_trip", response.sanitizedCode)
        assertEquals(0, harness.stopper.calls)
        assertEquals(0, harness.finisher.calls)
    }

    @Test
    fun finishTripWithMismatchedRemoteTripIdHasNoSideEffects() = runBlocking {
        val harness = finishHarness()

        val response = harness.coordinator.finishTrip(finishRequest("request-finish-mismatch-001", "command-finish-mismatch-001", "trip-other-999"), RESPONSE_CLOCK)

        assertEquals(PhoneActionResult.TRIP_MISMATCH, response.result)
        assertEquals("trip_mismatch", response.sanitizedCode)
        assertEquals(0, harness.stopper.calls)
        assertEquals(0, harness.finisher.calls)
        assertEquals("trip-real-123", harness.remoteTripStore.remoteTripId.value)
        assertEquals(TripSessionState.Active(TEST_TRIP_SESSION_KEY), harness.tripSessionStore.states.value)
    }

    @Test
    fun finishTripStopsMonitoringAndFinishesRemoteTrip() = runBlocking {
        val harness = finishHarness()
        val request = finishRequest("request-finish-001", "command-finish-777", "trip-real-123")

        val response = harness.coordinator.finishTrip(request, RESPONSE_CLOCK)

        assertEquals(PhoneActionResult.OK, response.result)
        assertEquals(request.requestId, response.requestId)
        assertNull(response.sanitizedCode)
        assertNull(response.remoteTripId)
        assertEquals(1, harness.stopper.calls)
        assertEquals(1, harness.finisher.calls)
        assertEquals(listOf("trip-real-123"), harness.finisher.tripIds)
        assertNull(harness.remoteTripStore.remoteTripId.value)
        assertEquals(TripSessionState.Idle, harness.tripSessionStore.states.value)
        assertEquals(WearCommandState.TerminalSuccess, harness.commandStore.find(request.commandId, WearCommandAction.FinishTrip)?.state)
    }

    @Test
    fun finishTripSameCommandIdWithNewRequestIdReusesTerminalResult() = runBlocking {
        val harness = finishHarness()
        val first = finishRequest("request-finish-001", "command-finish-shared", "trip-real-123")
        val retry = finishRequest("request-finish-002", "command-finish-shared", "trip-real-123")

        harness.coordinator.finishTrip(first, RESPONSE_CLOCK)
        val response = harness.coordinator.finishTrip(retry, RESPONSE_CLOCK + 1L)

        assertEquals(PhoneActionResult.OK, response.result)
        assertEquals("request-finish-002", response.requestId)
        assertEquals(1, harness.stopper.calls)
        assertEquals(1, harness.finisher.calls)
    }

    @Test
    fun finishTripMonitoringStopFailureOccursOnlyAfterRemoteFinish() = runBlocking {
        val harness = finishHarness(stopResult = MonitoringServiceStopResult.Failed)
        val request = finishRequest("request-finish-stop-001", "command-finish-stop-001", "trip-real-123")

        val response = harness.coordinator.finishTrip(request, RESPONSE_CLOCK)

        assertEquals(PhoneActionResult.RETRYABLE_ERROR, response.result)
        assertEquals("monitoring_stop_retry_required_after_remote_finish", response.sanitizedCode)
        assertEquals(1, harness.stopper.calls)
        assertEquals(1, harness.finisher.calls)
        assertEquals("trip-real-123", harness.remoteTripStore.remoteTripId.value)
        assertEquals(WearCommandState.PartialSideEffect, harness.commandStore.find(request.commandId, WearCommandAction.FinishTrip)?.state)
    }


    @Test
    fun finishTripRetryAfterMonitoringStopFailureDoesNotFinishRemoteTwice() = runBlocking {
        val harness = finishHarness(stopResult = MonitoringServiceStopResult.Failed)
        val first = finishRequest("request-finish-stop-001", "command-finish-stop-shared", "trip-real-123")
        val retry = finishRequest("request-finish-stop-002", "command-finish-stop-shared", "trip-real-123")

        val firstResponse = harness.coordinator.finishTrip(first, RESPONSE_CLOCK)
        assertEquals("monitoring_stop_retry_required_after_remote_finish", firstResponse.sanitizedCode)
        assertEquals(1, harness.finisher.calls)
        assertEquals(1, harness.stopper.calls)

        harness.stopper.result = MonitoringServiceStopResult.Stopped
        val retryResponse = harness.coordinator.finishTrip(retry, RESPONSE_CLOCK + 1L)

        assertEquals(PhoneActionResult.OK, retryResponse.result)
        assertEquals(1, harness.finisher.calls)
        assertEquals(2, harness.stopper.calls)
        assertNull(harness.remoteTripStore.remoteTripId.value)
        assertEquals(TripSessionState.Idle, harness.tripSessionStore.states.value)
    }

    @Test
    fun finishTripRemoteFailureKeepsMonitoringAndPreservesTrip() = runBlocking {
        val harness = finishHarness(
            remoteTripId = "trip-partial-finish-123",
            finisherResult = TripMutationResult.NetworkUnavailable("network_unavailable"),
        )
        val request = finishRequest("request-finish-partial-001", "command-finish-partial-001", "trip-partial-finish-123")

        val response = harness.coordinator.finishTrip(request, RESPONSE_CLOCK)

        assertEquals(PhoneActionResult.RETRYABLE_ERROR, response.result)
        assertEquals("network_unavailable", response.sanitizedCode)
        assertEquals("trip-partial-finish-123", harness.remoteTripStore.remoteTripId.value)
        assertEquals(0, harness.stopper.calls)
        assertEquals(1, harness.finisher.calls)
        assertEquals(WearCommandState.Retryable, harness.commandStore.find(request.commandId, WearCommandAction.FinishTrip)?.state)
        assertEquals(TripSessionState.Active(TEST_TRIP_SESSION_KEY), harness.tripSessionStore.states.value)
    }

    @Test
    fun finishTripRetryAfterRemoteFailureUsesSameTripAndSucceeds() = runBlocking {
        val harness = finishHarness(
            remoteTripId = "trip-partial-finish-123",
            finisherResult = TripMutationResult.NetworkUnavailable("network_unavailable"),
        )
        val first = finishRequest("request-finish-partial-001", "command-finish-partial-001", "trip-partial-finish-123")
        val retry = finishRequest("request-finish-partial-002", "command-finish-partial-001", "trip-partial-finish-123")

        harness.coordinator.finishTrip(first, RESPONSE_CLOCK)
        harness.stopper.result = MonitoringServiceStopResult.Stopped
        harness.finisher.result = TripMutationResult.Success("trip-partial-finish-123", "Finished")
        val response = harness.coordinator.finishTrip(retry, RESPONSE_CLOCK + 1L)

        assertEquals(PhoneActionResult.OK, response.result)
        assertEquals("request-finish-partial-002", response.requestId)
        assertEquals(listOf("trip-partial-finish-123", "trip-partial-finish-123"), harness.finisher.tripIds)
        assertEquals(2, harness.finisher.calls)
        assertNull(harness.remoteTripStore.remoteTripId.value)
        assertEquals(WearCommandState.TerminalSuccess, harness.commandStore.find(retry.commandId, WearCommandAction.FinishTrip)?.state)
    }

    @Test
    fun finishTripWithNewCommandAfterCompletionReturnsNoActiveTrip() = runBlocking {
        val harness = finishHarness()
        harness.coordinator.finishTrip(finishRequest("request-finish-001", "command-finish-001", "trip-real-123"), RESPONSE_CLOCK)

        val response = harness.coordinator.finishTrip(finishRequest("request-finish-002", "command-finish-002", "trip-real-123"), RESPONSE_CLOCK + 1L)

        assertEquals(PhoneActionResult.NO_ACTIVE_TRIP, response.result)
        assertEquals("no_active_trip", response.sanitizedCode)
        assertEquals(1, harness.stopper.calls)
        assertEquals(1, harness.finisher.calls)
    }

    @Test fun manualSosLoggedOutReturnsNotAuthenticatedWithoutSideEffects() = runBlocking {
        val h = manualHarness(session = SessionState.LoggedOut)
        val r = h.coordinator.manualSos(manualRequest("request-sos-auth-001", "command-sos-auth-001"), RESPONSE_CLOCK)
        assertEquals(PhoneActionResult.NOT_AUTHENTICATED, r.result); assertEquals("manual_sos_not_authenticated", r.sanitizedCode)
        assertEquals("request-sos-auth-001", r.requestId); assertEquals(0, h.requester.calls); assertEquals("trip-real-123", h.remoteTripStore.remoteTripId.value)
    }

    @Test fun manualSosMonitorReturnsWrongRoleWithoutSideEffects() = runBlocking {
        val h = manualHarness(session = authenticated(UserRole.Monitor))
        val r = h.coordinator.manualSos(manualRequest("request-sos-role-001", "command-sos-role-001"), RESPONSE_CLOCK)
        assertEquals(PhoneActionResult.WRONG_ROLE, r.result); assertEquals("manual_sos_wrong_role", r.sanitizedCode)
        assertEquals(0, h.requester.calls); assertEquals("trip-real-123", h.remoteTripStore.remoteTripId.value)
    }

    @Test fun manualSosWithoutActiveTripReturnsNoActiveTrip() = runBlocking {
        val h = manualHarness(remoteTripStore = InMemoryRemoteTripSessionStore())
        val r = h.coordinator.manualSos(manualRequest("request-sos-none-001", "command-sos-none-001", "trip-stale"), RESPONSE_CLOCK)
        assertEquals(PhoneActionResult.NO_ACTIVE_TRIP, r.result); assertEquals("no_active_trip", r.sanitizedCode); assertEquals(0, h.requester.calls)
    }

    @Test fun manualSosWithMismatchedTripHasNoSideEffects() = runBlocking {
        val h = manualHarness()
        val r = h.coordinator.manualSos(manualRequest("request-sos-mismatch-001", "command-sos-mismatch-001", "trip-other-999"), RESPONSE_CLOCK)
        assertEquals(PhoneActionResult.TRIP_MISMATCH, r.result); assertEquals("trip_mismatch", r.sanitizedCode); assertEquals(0, h.requester.calls)
        assertEquals("trip-real-123", h.remoteTripStore.remoteTripId.value); assertEquals(TripSessionState.Active(TEST_TRIP_SESSION_KEY), h.tripSessionStore.states.value)
    }

    @Test fun manualSosCreatesIncidentAndReturnsOk() = runBlocking {
        val h = manualHarness(); val q = manualRequest("request-sos-001", "command-sos-001")
        val r = h.coordinator.manualSos(q, RESPONSE_CLOCK)
        assertEquals(PhoneActionResult.OK, r.result); assertEquals(q.requestId, r.requestId); assertNull(r.sanitizedCode); assertEquals("trip-real-123", r.remoteTripId)
        assertEquals(1, h.requester.calls); assertEquals("trip-real-123", h.remoteTripStore.remoteTripId.value); assertEquals(TripSessionState.Active(TEST_TRIP_SESSION_KEY), h.tripSessionStore.states.value)
        assertEquals(WearCommandState.TerminalSuccess, h.commandStore.find(q.commandId, WearCommandAction.ManualSos)?.state)
    }

    @Test fun manualSosSameCommandIdWithNewRequestIdReusesTerminalResult() = runBlocking {
        val h = manualHarness(); h.coordinator.manualSos(manualRequest("request-sos-001", "command-sos-shared"), RESPONSE_CLOCK)
        val r = h.coordinator.manualSos(manualRequest("request-sos-002", "command-sos-shared"), RESPONSE_CLOCK + 1)
        assertEquals(PhoneActionResult.OK, r.result); assertEquals("request-sos-002", r.requestId); assertEquals("trip-real-123", r.remoteTripId); assertEquals(1, h.requester.calls)
    }

    @Test fun manualSosMissingRequiredDataReturnsPhoneActionRequired() = runBlocking {
        val h = manualHarness(result = incident(IncidentRemoteCreationStatus.MissingRequiredData("manual_sos_location_missing")))
        val q = manualRequest("request-sos-location-001", "command-sos-location-001"); val r = h.coordinator.manualSos(q, RESPONSE_CLOCK)
        assertEquals(PhoneActionResult.PHONE_ACTION_REQUIRED, r.result); assertEquals("manual_sos_location_missing", r.sanitizedCode); assertEquals("trip-real-123", r.remoteTripId)
        assertEquals(WearCommandState.TerminalRejected, h.commandStore.find(q.commandId, WearCommandAction.ManualSos)?.state)
    }

    @Test fun manualSosTransientFailureReturnsRetryable() = runBlocking {
        val h = manualHarness(result = incident(IncidentRemoteCreationStatus.NetworkUnavailable("network_unavailable"))); val q = manualRequest("request-sos-net-001", "command-sos-net-001")
        val r = h.coordinator.manualSos(q, RESPONSE_CLOCK)
        assertEquals(PhoneActionResult.RETRYABLE_ERROR, r.result); assertEquals("network_unavailable", r.sanitizedCode); assertEquals("trip-real-123", r.remoteTripId)
        assertEquals(WearCommandState.Retryable, h.commandStore.find(q.commandId, WearCommandAction.ManualSos)?.state)
    }

    @Test fun manualSosRetryableResultAllowsRetry() = runBlocking {
        val h = manualHarness(result = incident(IncidentRemoteCreationStatus.NetworkUnavailable("network_unavailable")))
        h.coordinator.manualSos(manualRequest("request-sos-retry-001", "command-sos-retry"), RESPONSE_CLOCK); h.requester.result = incident(IncidentRemoteCreationStatus.Success("incident-1"))
        val r = h.coordinator.manualSos(manualRequest("request-sos-retry-002", "command-sos-retry"), RESPONSE_CLOCK + 1)
        assertEquals(PhoneActionResult.OK, r.result); assertEquals("request-sos-retry-002", r.requestId); assertEquals(2, h.requester.calls)
        assertEquals(WearCommandState.TerminalSuccess, h.commandStore.find("command-sos-retry", WearCommandAction.ManualSos)?.state)
    }

    @Test fun manualSosPartialSideEffectIsPreservedForRetry() = runBlocking {
        val h = manualHarness(result = incident(IncidentRemoteCreationStatus.InvalidResponse("manual_sos_result_persistence_failed"))); val q = manualRequest("request-sos-partial-001", "command-sos-partial")
        val r = h.coordinator.manualSos(q, RESPONSE_CLOCK)
        assertEquals(PhoneActionResult.RETRYABLE_ERROR, r.result); assertEquals("manual_sos_result_persistence_failed", r.sanitizedCode); assertEquals("trip-real-123", r.remoteTripId)
        assertEquals(WearCommandState.PartialSideEffect, h.commandStore.find(q.commandId, WearCommandAction.ManualSos)?.state)
    }

    @Test fun manualSosRetryAfterPartialSideEffectRunsCoordinatorAgain() = runBlocking {
        val h = manualHarness(result = incident(IncidentRemoteCreationStatus.InvalidResponse("manual_sos_result_persistence_failed")))
        h.coordinator.manualSos(manualRequest("request-sos-partial-001", "command-sos-partial"), RESPONSE_CLOCK); h.requester.result = incident(IncidentRemoteCreationStatus.Success("incident-1"))
        val r = h.coordinator.manualSos(manualRequest("request-sos-partial-002", "command-sos-partial"), RESPONSE_CLOCK + 1)
        assertEquals(PhoneActionResult.OK, r.result); assertEquals("request-sos-partial-002", r.requestId); assertEquals(2, h.requester.calls)
        assertEquals(WearCommandState.TerminalSuccess, h.commandStore.find("command-sos-partial", WearCommandAction.ManualSos)?.state)
    }

    private fun coordinator(
        session: SessionState,
        remoteTripStore: InMemoryRemoteTripSessionStore = InMemoryRemoteTripSessionStore(),
        clockMillis: Long = 10L,
    ): WearPhoneActionCoordinator = WearPhoneActionCoordinator(
        authRepository = FakeAuthRepository(session),
        remoteTripStore = remoteTripStore,
        clock = { clockMillis },
    )

    private fun startHarness(
        session: SessionState = authenticated(UserRole.Rider),
        remoteTripStore: InMemoryRemoteTripSessionStore = InMemoryRemoteTripSessionStore(),
        remoteTripId: String = "trip-real-123",
        remoteResult: TripMutationResult = TripMutationResult.Success(remoteTripId, "Active"),
        monitoringResult: MonitoringServiceStartResult = MonitoringServiceStartResult.Started,
        locationStatus: BackgroundLocationPermissionStatus = BackgroundLocationPermissionStatus.Granted,
    ): StartHarness {
        val remoteStarter = FakeResolvedStarter(remoteTripStore, remoteResult)
        val monitoringStarter = FakeMonitoringStarter(monitoringResult)
        val commandStore = SharedPreferencesWearCommandResultStore(InMemoryCommandStorage(), clock = { STORE_CLOCK })
        val dependencies = WearStartTripDependencies(
            resolvedStarter = remoteStarter,
            monitoringServiceStarter = monitoringStarter,
            tripSessionStore = InMemoryTripSessionStore(),
            locationStatusProvider = BackgroundLocationPermissionStatusProvider { locationStatus },
            notificationStatusProvider = AppNotificationStatusProvider { AppNotificationStatus.Enabled },
            bluetoothStatusProvider = BluetoothRequirementStatusProvider { BluetoothRequirementStatus.Enabled },
            commandStore = commandStore,
            now = { STORE_CLOCK },
        )
        return StartHarness(
            coordinator = WearPhoneActionCoordinator(FakeAuthRepository(session), remoteTripStore, clock = { RESPONSE_CLOCK }, startDependencies = dependencies),
            remoteTripStore = remoteTripStore,
            remoteStarter = remoteStarter,
            monitoringStarter = monitoringStarter,
            commandStore = commandStore,
        )
    }

    private fun startRequest(requestId: String, commandId: String) =
        StartTripActionRequest(requestId, commandId, requestedAtEpochMs = 1L)

    private fun finishHarness(
        session: SessionState = authenticated(UserRole.Rider),
        remoteTripId: String = "trip-real-123",
        remoteTripStore: InMemoryRemoteTripSessionStore = InMemoryRemoteTripSessionStore().apply { setActiveSession(remoteTripId, null, TEST_TRIP_SESSION_KEY) },
        stopResult: MonitoringServiceStopResult = MonitoringServiceStopResult.Stopped,
        finisherResult: TripMutationResult = TripMutationResult.Success(remoteTripId, "Finished"),
    ): FinishHarness {
        val stopper = FakeMonitoringStopper(stopResult)
        val finisher = FakeRemoteFinisher(remoteTripStore, finisherResult)
        val commandStore = SharedPreferencesWearCommandResultStore(InMemoryCommandStorage(), clock = { STORE_CLOCK })
        val tripSessionStore = InMemoryTripSessionStore(TripSessionState.Active(TEST_TRIP_SESSION_KEY))
        val dependencies = WearFinishTripDependencies(
            finisher = finisher,
            monitoringServiceStopper = stopper,
            tripSessionStore = tripSessionStore,
            commandStore = commandStore,
            finishRequestFactory = { FinishTripRequestDto(clientFinishedAtUtc = "2026-08-15T00:00:00Z") },
            reconcileLocalFinishedTrip = { finishedTripId ->
                val active = tripSessionStore.states.value as? TripSessionState.Active
                if (active == null || active.tripSessionKey != TEST_TRIP_SESSION_KEY) {
                    false
                } else {
                    val remoteCleared = remoteTripStore.clearIfMatches(TEST_TRIP_SESSION_KEY, finishedTripId)
                    val sessionCleared = tripSessionStore.setIdleIfMatches(TEST_TRIP_SESSION_KEY)
                    remoteCleared != com.example.sos_segundoplano.data.remote.trip.RemoteTripSessionClearResult.DifferentTrip &&
                        remoteCleared != com.example.sos_segundoplano.data.remote.trip.RemoteTripSessionClearResult.LegacyUncorrelated &&
                        sessionCleared != com.example.sos_segundoplano.data.trip.TripSessionClearResult.DifferentTrip
                }
            },
            now = { STORE_CLOCK },
        )
        return FinishHarness(
            coordinator = WearPhoneActionCoordinator(
                FakeAuthRepository(session),
                remoteTripStore,
                clock = { RESPONSE_CLOCK },
                finishDependencies = dependencies,
            ),
            remoteTripStore = remoteTripStore,
            stopper = stopper,
            finisher = finisher,
            tripSessionStore = tripSessionStore,
            commandStore = commandStore,
        )
    }

    private fun finishRequest(requestId: String, commandId: String, remoteTripId: String) =
        FinishTripActionRequest(requestId, commandId, remoteTripId, requestedAtEpochMs = 1L)

    private fun manualHarness(session: SessionState = authenticated(UserRole.Rider), remoteTripStore: InMemoryRemoteTripSessionStore = InMemoryRemoteTripSessionStore().apply { setRemoteTripId("trip-real-123") }, result: LocalIncident = incident(IncidentRemoteCreationStatus.Success("incident-1"))): ManualHarness {
        val requester = FakeManualSosRequester(result); val commandStore = SharedPreferencesWearCommandResultStore(InMemoryCommandStorage(), clock = { STORE_CLOCK }); val tripSessionStore = InMemoryTripSessionStore(TripSessionState.Active(TEST_TRIP_SESSION_KEY))
        val coordinator = WearPhoneActionCoordinator(FakeAuthRepository(session), remoteTripStore, clock = { RESPONSE_CLOCK }, manualSosDependencies = WearManualSosDependencies(requester::request, commandStore, now = { STORE_CLOCK }))
        return ManualHarness(coordinator, remoteTripStore, requester, tripSessionStore, commandStore)
    }

    private fun manualRequest(requestId: String, commandId: String, remoteTripId: String = "trip-real-123") = ManualSosActionRequest(requestId, commandId, remoteTripId, requestedAtEpochMs = 1L)

    private fun incident(status: IncidentRemoteCreationStatus) = LocalIncident(1, 0, 0, 0, 0, IncidentCause.ManualSos, null, RiskLevel.Unknown, 0.0, emptyList(), "test", "test", GpsQualityStatus.Unavailable, false, remoteCreationStatus = status)

    private fun authenticated(role: UserRole): SessionState.Authenticated = SessionState.Authenticated(
        user = user(role),
        accessTokenExpiresAt = Instant.EPOCH,
        rememberMe = true,
    )

    private fun refreshing(role: UserRole): SessionState.Refreshing = SessionState.Refreshing(
        user = user(role),
        accessTokenExpiresAt = Instant.EPOCH,
        rememberMe = true,
    )

    private fun user(role: UserRole) = AuthUser(
        id = "user-1",
        email = "user@example.com",
        fullName = "Test User",
        phoneNumber = "",
        role = role,
        isActive = true,
    )

    private class FakeAuthRepository(session: SessionState) : AuthRepository {
        private val sessions = MutableStateFlow(session)

        override suspend fun login(email: String, password: String, rememberMe: Boolean): AuthResult<AuthUser> = error("unused")
        override suspend fun restoreSession(): AuthResult<AuthUser?> = error("unused")
        override suspend fun ensureValidAccessToken(): AuthResult<AccessToken> = error("unused")
        override suspend fun refreshSession(): AuthResult<AuthUser> = error("unused")
        override suspend fun logout(): AuthResult<Unit> = error("unused")
        override fun observeSession(): StateFlow<SessionState> = sessions
    }

    private class FakeResolvedStarter(
        private val remoteTripStore: InMemoryRemoteTripSessionStore,
        private val result: TripMutationResult,
    ) : ResolvedRemoteTripStarter {
        var calls = 0
        override suspend fun startTrip(): TripMutationResult {
            calls++
            if (result is TripMutationResult.Success) remoteTripStore.setRemoteTripId(result.remoteTripId)
            return result
        }
    }

    private class FakeMonitoringStarter(
        var result: MonitoringServiceStartResult,
    ) : MonitoringServiceStarter {
        var calls = 0
        override fun start(): MonitoringServiceStartResult {
            calls++
            return result
        }
    }

    private class FakeMonitoringStopper(
        var result: MonitoringServiceStopResult,
    ) : MonitoringServiceStopper {
        var calls = 0
        override fun stop(): MonitoringServiceStopResult {
            calls++
            return result
        }
    }

    private class FakeRemoteFinisher(
        private val remoteTripStore: InMemoryRemoteTripSessionStore,
        var result: TripMutationResult,
    ) : RemoteTripIdFinisher {
        var calls = 0
        val tripIds = mutableListOf<String?>()

        override suspend fun finishTrip(request: FinishTripRequestDto): TripMutationResult {
            val remoteTripId = remoteTripStore.remoteTripId.value
                ?: return TripMutationResult.MissingRequiredData("remote_trip_id_missing")
            return finishTrip(remoteTripId, request)
        }

        override suspend fun finishTrip(remoteTripId: String, request: FinishTripRequestDto): TripMutationResult {
            calls++
            tripIds += remoteTripId
            return result
        }
    }

    private class FakeManualSosRequester(var result: LocalIncident) { var calls = 0; suspend fun request(): LocalIncident { calls++; return result } }

    private class InMemoryCommandStorage : WearCommandRecordStorage {
        private var records: List<WearCommandRecord> = emptyList()
        override fun read(): List<WearCommandRecord> = records
        override fun write(records: List<WearCommandRecord>) {
            this.records = records
        }
    }

    private data class StartHarness(
        val coordinator: WearPhoneActionCoordinator,
        val remoteTripStore: InMemoryRemoteTripSessionStore,
        val remoteStarter: FakeResolvedStarter,
        val monitoringStarter: FakeMonitoringStarter,
        val commandStore: WearCommandResultStore,
    )

    private data class FinishHarness(
        val coordinator: WearPhoneActionCoordinator,
        val remoteTripStore: InMemoryRemoteTripSessionStore,
        val stopper: FakeMonitoringStopper,
        val finisher: FakeRemoteFinisher,
        val tripSessionStore: InMemoryTripSessionStore,
        val commandStore: WearCommandResultStore,
    )

    private data class ManualHarness(val coordinator: WearPhoneActionCoordinator, val remoteTripStore: InMemoryRemoteTripSessionStore, val requester: FakeManualSosRequester, val tripSessionStore: InMemoryTripSessionStore, val commandStore: WearCommandResultStore)

    private companion object {
        const val RESPONSE_CLOCK = 1_723_766_400_000L
        const val STORE_CLOCK = 1_723_766_400_100L
        const val TEST_TRIP_SESSION_KEY = "trip-session-test"
    }
}
