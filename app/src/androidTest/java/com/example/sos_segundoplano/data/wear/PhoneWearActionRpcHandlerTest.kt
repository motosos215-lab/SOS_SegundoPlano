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
import com.example.sos_segundoplano.data.remote.trip.RemoteTripFinisher
import com.example.sos_segundoplano.data.remote.trip.ResolvedRemoteTripStarter
import com.example.sos_segundoplano.data.remote.trip.TripMutationResult
import com.example.sos_segundoplano.data.trip.InMemoryTripSessionStore
import com.example.sos_segundoplano.domain.auth.AccessToken
import com.example.sos_segundoplano.domain.auth.AuthResult
import com.example.sos_segundoplano.domain.auth.AuthUser
import com.example.sos_segundoplano.domain.auth.SessionState
import com.example.sos_segundoplano.domain.auth.UserRole
import com.example.sos_segundoplano.domain.model.TripSessionState
import com.example.sos_segundoplano.domain.repository.AuthRepository
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
import com.example.sos_segundoplano.wearprotocol.WearActionProtocolCodec
import com.example.sos_segundoplano.wearprotocol.WearDecodeResult
import com.example.sos_segundoplano.wearprotocol.WearProtocol
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneWearActionRpcHandlerTest {

    @Test
    fun tripStateInactiveRoundTrip() = runBlocking {
        val handler = handler(session = authenticatedRider())
        val request = TripStateRequest("request-state-inactive-001", 1L)

        val response = handler.handle(
            WearProtocol.PATH_TRIP_STATE,
            WearActionProtocolCodec.encodeTripStateRequest(request),
        )

        val parsed = WearActionProtocolCodec.decodeTripStateResponse(requireNotNull(response)).success()
        assertEquals(request.requestId, parsed.requestId)
        assertFalse(parsed.active)
        assertNull(parsed.remoteTripId)
        assertNull(parsed.startedAtEpochMs)
        assertEquals(COORDINATOR_CLOCK, parsed.updatedAtEpochMs)
    }

    @Test
    fun tripStateActiveRoundTrip() = runBlocking {
        val tripStore = InMemoryRemoteTripSessionStore().apply { setRemoteTripId("trip-real-123") }
        val handler = handler(session = authenticatedRider(), tripStore = tripStore)
        val request = TripStateRequest("request-state-active-001", 1L)

        val response = handler.handle(
            WearProtocol.PATH_TRIP_STATE,
            WearActionProtocolCodec.encodeTripStateRequest(request),
        )

        val parsed = WearActionProtocolCodec.decodeTripStateResponse(requireNotNull(response)).success()
        assertEquals(request.requestId, parsed.requestId)
        assertTrue(parsed.active)
        assertEquals("trip-real-123", parsed.remoteTripId)
        assertNull(parsed.startedAtEpochMs)
    }

    @Test
    fun startActionCreatesTripAndReturnsOk() = runBlocking {
        val harness = startHarness()
        val request = StartTripActionRequest("request-start-rpc-001", "command-start-rpc-001", 1L)

        val response = harness.handler.handle(
            WearProtocol.PATH_ACTION_START_TRIP,
            WearActionProtocolCodec.encodeStartTripAction(request),
        )

        val parsed = WearActionProtocolCodec.decodePhoneActionResponse(
            requireNotNull(response),
            WearProtocol.PATH_ACTION_START_TRIP,
        ).success()
        assertEquals(PhoneActionResult.OK, parsed.result)
        assertEquals("request-start-rpc-001", parsed.requestId)
        assertEquals("trip-rpc-123", parsed.remoteTripId)
        assertNull(parsed.sanitizedCode)
        assertEquals(1, harness.remoteStarter.calls)
        assertEquals(1, harness.monitoringStarter.calls)
        assertEquals("trip-rpc-123", harness.tripStore.remoteTripId.value)
    }

    @Test
    fun startActionRetryWithSameCommandDoesNotCreateSecondTrip() = runBlocking {
        val harness = startHarness()
        val first = StartTripActionRequest("request-start-rpc-001", "command-start-rpc-shared", 1L)
        val retry = StartTripActionRequest("request-start-rpc-002", "command-start-rpc-shared", 2L)

        harness.handler.handle(
            WearProtocol.PATH_ACTION_START_TRIP,
            WearActionProtocolCodec.encodeStartTripAction(first),
        )
        val response = harness.handler.handle(
            WearProtocol.PATH_ACTION_START_TRIP,
            WearActionProtocolCodec.encodeStartTripAction(retry),
        )

        val parsed = WearActionProtocolCodec.decodePhoneActionResponse(
            requireNotNull(response),
            WearProtocol.PATH_ACTION_START_TRIP,
        ).success()
        assertEquals(PhoneActionResult.OK, parsed.result)
        assertEquals("request-start-rpc-002", parsed.requestId)
        assertEquals("trip-rpc-123", parsed.remoteTripId)
        assertEquals(1, harness.remoteStarter.calls)
        assertEquals(1, harness.monitoringStarter.calls)
    }

    @Test
    fun finishActionFinishesTripAndReturnsOk() = runBlocking {
        val harness = finishHarness()
        val request = FinishTripActionRequest("request-finish-rpc-001", "command-finish-rpc-001", "trip-finish-rpc-123", 1L)

        val response = harness.handler.handle(
            WearProtocol.PATH_ACTION_FINISH_TRIP,
            WearActionProtocolCodec.encodeFinishTripAction(request),
        )

        val parsed = WearActionProtocolCodec.decodePhoneActionResponse(
            requireNotNull(response),
            WearProtocol.PATH_ACTION_FINISH_TRIP,
        ).success()
        assertEquals(PhoneActionResult.OK, parsed.result)
        assertEquals("request-finish-rpc-001", parsed.requestId)
        assertNull(parsed.sanitizedCode)
        assertNull(parsed.remoteTripId)
        assertEquals(1, harness.stopper.calls)
        assertEquals(1, harness.finisher.calls)
        assertEquals(listOf("trip-finish-rpc-123"), harness.finisher.tripIds)
        assertNull(harness.tripStore.remoteTripId.value)
        assertEquals(TripSessionState.Idle, harness.tripSessionStore.states.value)
    }

    @Test
    fun finishActionRetryWithSameCommandDoesNotRepeatSideEffects() = runBlocking {
        val harness = finishHarness()
        val first = FinishTripActionRequest("request-finish-rpc-001", "command-finish-rpc-shared", "trip-finish-rpc-123", 1L)
        val retry = FinishTripActionRequest("request-finish-rpc-002", "command-finish-rpc-shared", "trip-finish-rpc-123", 2L)

        harness.handler.handle(
            WearProtocol.PATH_ACTION_FINISH_TRIP,
            WearActionProtocolCodec.encodeFinishTripAction(first),
        )
        val response = harness.handler.handle(
            WearProtocol.PATH_ACTION_FINISH_TRIP,
            WearActionProtocolCodec.encodeFinishTripAction(retry),
        )

        val parsed = WearActionProtocolCodec.decodePhoneActionResponse(
            requireNotNull(response),
            WearProtocol.PATH_ACTION_FINISH_TRIP,
        ).success()
        assertEquals(PhoneActionResult.OK, parsed.result)
        assertEquals("request-finish-rpc-002", parsed.requestId)
        assertNull(parsed.sanitizedCode)
        assertEquals(1, harness.stopper.calls)
        assertEquals(1, harness.finisher.calls)
    }

    @Test
    fun finishActionWithMismatchedTripIsRejectedWithoutSideEffects() = runBlocking {
        val harness = finishHarness(remoteTripId = "trip-finish-rpc-real")
        val request = FinishTripActionRequest("request-finish-rpc-mismatch", "command-finish-rpc-mismatch", "trip-finish-rpc-other", 1L)

        val response = harness.handler.handle(
            WearProtocol.PATH_ACTION_FINISH_TRIP,
            WearActionProtocolCodec.encodeFinishTripAction(request),
        )

        val parsed = WearActionProtocolCodec.decodePhoneActionResponse(
            requireNotNull(response),
            WearProtocol.PATH_ACTION_FINISH_TRIP,
        ).success()
        assertEquals(PhoneActionResult.TRIP_MISMATCH, parsed.result)
        assertEquals("request-finish-rpc-mismatch", parsed.requestId)
        assertEquals("trip_mismatch", parsed.sanitizedCode)
        assertEquals(0, harness.stopper.calls)
        assertEquals(0, harness.finisher.calls)
        assertEquals("trip-finish-rpc-real", harness.tripStore.remoteTripId.value)
    }

    @Test
    fun manualSosActionCreatesIncidentAndReturnsOk() = runBlocking {
        val harness = manualSosHarness()
        val request = ManualSosActionRequest("request-sos-rpc-001", "command-sos-rpc-001", "trip-sos-rpc-123", 1L)

        val response = harness.handler.handle(
            WearProtocol.PATH_ACTION_MANUAL_SOS,
            WearActionProtocolCodec.encodeManualSosAction(request),
        )

        val parsed = WearActionProtocolCodec.decodePhoneActionResponse(requireNotNull(response), WearProtocol.PATH_ACTION_MANUAL_SOS).success()
        assertEquals(PhoneActionResult.OK, parsed.result); assertEquals("request-sos-rpc-001", parsed.requestId)
        assertNull(parsed.sanitizedCode); assertEquals("trip-sos-rpc-123", parsed.remoteTripId)
        assertEquals(1, harness.requester.calls); assertEquals("trip-sos-rpc-123", harness.tripStore.remoteTripId.value)
    }

    @Test
    fun manualSosActionRetryWithSameCommandDoesNotCreateSecondIncidentRequest() = runBlocking {
        val harness = manualSosHarness()
        val first = ManualSosActionRequest("request-sos-rpc-001", "command-sos-rpc-shared", "trip-sos-rpc-123", 1L)
        val retry = ManualSosActionRequest("request-sos-rpc-002", "command-sos-rpc-shared", "trip-sos-rpc-123", 2L)
        harness.handler.handle(WearProtocol.PATH_ACTION_MANUAL_SOS, WearActionProtocolCodec.encodeManualSosAction(first))
        val response = harness.handler.handle(WearProtocol.PATH_ACTION_MANUAL_SOS, WearActionProtocolCodec.encodeManualSosAction(retry))
        val parsed = WearActionProtocolCodec.decodePhoneActionResponse(requireNotNull(response), WearProtocol.PATH_ACTION_MANUAL_SOS).success()
        assertEquals(PhoneActionResult.OK, parsed.result); assertEquals("request-sos-rpc-002", parsed.requestId); assertEquals("trip-sos-rpc-123", parsed.remoteTripId)
        assertEquals(1, harness.requester.calls)
    }

    @Test
    fun manualSosActionWithMismatchedTripIsRejectedWithoutSideEffects() = runBlocking {
        val harness = manualSosHarness(remoteTripId = "trip-sos-rpc-real")
        val request = ManualSosActionRequest("request-sos-mismatch-001", "command-sos-mismatch-001", "trip-sos-rpc-other", 1L)
        val response = harness.handler.handle(WearProtocol.PATH_ACTION_MANUAL_SOS, WearActionProtocolCodec.encodeManualSosAction(request))
        val parsed = WearActionProtocolCodec.decodePhoneActionResponse(requireNotNull(response), WearProtocol.PATH_ACTION_MANUAL_SOS).success()
        assertEquals(PhoneActionResult.TRIP_MISMATCH, parsed.result); assertEquals("trip_mismatch", parsed.sanitizedCode); assertEquals("request-sos-mismatch-001", parsed.requestId)
        assertEquals(0, harness.requester.calls); assertEquals("trip-sos-rpc-real", harness.tripStore.remoteTripId.value)
    }

    @Test
    fun unknownPathIsRejected() = runBlocking {
        val response = handler(session = authenticatedRider()).handle(
            "/motosos/v1/unknown",
            ByteArray(0),
        )

        assertNull(response)
    }

    @Test
    fun invalidTripStatePayloadIsRejected() = runBlocking {
        val response = handler(session = authenticatedRider()).handle(
            WearProtocol.PATH_TRIP_STATE,
            byteArrayOf(0x01, 0x02, 0x03),
        )

        assertNull(response)
    }

    private fun assertUnavailableResponse(response: ByteArray?, path: String, requestId: String) {
        val parsed = WearActionProtocolCodec.decodePhoneActionResponse(
            requireNotNull(response),
            path,
        ).success()
        assertEquals(requestId, parsed.requestId)
        assertEquals(PhoneActionResult.UNAVAILABLE, parsed.result)
        assertEquals("action_not_available", parsed.sanitizedCode)
        assertNull(parsed.remoteTripId)
        assertEquals(HANDLER_CLOCK, parsed.respondedAtEpochMs)
    }

    private fun handler(
        session: SessionState,
        tripStore: InMemoryRemoteTripSessionStore = InMemoryRemoteTripSessionStore(),
    ): PhoneWearActionRpcHandler = PhoneWearActionRpcHandler(
        coordinator = WearPhoneActionCoordinator(
            authRepository = FakeAuthRepository(session),
            remoteTripStore = tripStore,
            clock = { COORDINATOR_CLOCK },
        ),
        clock = { HANDLER_CLOCK },
    )

    private fun startHarness(): StartHarness {
        val tripStore = InMemoryRemoteTripSessionStore()
        val remoteStarter = FakeResolvedStarter(tripStore, "trip-rpc-123")
        val monitoringStarter = FakeMonitoringStarter()
        val commandStore = SharedPreferencesWearCommandResultStore(InMemoryCommandStorage(), clock = { STORE_CLOCK })
        val coordinator = WearPhoneActionCoordinator(
            authRepository = FakeAuthRepository(authenticatedRider()),
            remoteTripStore = tripStore,
            clock = { COORDINATOR_CLOCK },
            startDependencies = WearStartTripDependencies(
                resolvedStarter = remoteStarter,
                monitoringServiceStarter = monitoringStarter,
                tripSessionStore = InMemoryTripSessionStore(),
                locationStatusProvider = BackgroundLocationPermissionStatusProvider { BackgroundLocationPermissionStatus.Granted },
                notificationStatusProvider = AppNotificationStatusProvider { AppNotificationStatus.Enabled },
                bluetoothStatusProvider = BluetoothRequirementStatusProvider { BluetoothRequirementStatus.Enabled },
                commandStore = commandStore,
                now = { STORE_CLOCK },
            ),
        )
        return StartHarness(PhoneWearActionRpcHandler(coordinator, clock = { HANDLER_CLOCK }), tripStore, remoteStarter, monitoringStarter)
    }

    private fun finishHarness(
        remoteTripId: String = "trip-finish-rpc-123",
    ): FinishHarness {
        val tripStore = InMemoryRemoteTripSessionStore().apply { setRemoteTripId(remoteTripId) }
        val stopper = FakeMonitoringStopper()
        val finisher = FakeRemoteFinisher(tripStore)
        val tripSessionStore = InMemoryTripSessionStore(TripSessionState.Active)
        val commandStore = SharedPreferencesWearCommandResultStore(InMemoryCommandStorage(), clock = { STORE_CLOCK })
        val coordinator = WearPhoneActionCoordinator(
            authRepository = FakeAuthRepository(authenticatedRider()),
            remoteTripStore = tripStore,
            clock = { COORDINATOR_CLOCK },
            finishDependencies = WearFinishTripDependencies(
                finisher = finisher,
                monitoringServiceStopper = stopper,
                tripSessionStore = tripSessionStore,
                commandStore = commandStore,
                finishRequestFactory = { FinishTripRequestDto(clientFinishedAtUtc = "2026-08-15T00:00:00Z") },
                now = { STORE_CLOCK },
            ),
        )
        return FinishHarness(
            handler = PhoneWearActionRpcHandler(coordinator, clock = { HANDLER_CLOCK }),
            tripStore = tripStore,
            stopper = stopper,
            finisher = finisher,
            tripSessionStore = tripSessionStore,
        )
    }

    private fun manualSosHarness(remoteTripId: String = "trip-sos-rpc-123"): ManualSosHarness {
        val tripStore = InMemoryRemoteTripSessionStore().apply { setRemoteTripId(remoteTripId) }
        val requester = FakeManualSosRequester()
        val commandStore = SharedPreferencesWearCommandResultStore(InMemoryCommandStorage(), clock = { STORE_CLOCK })
        val coordinator = WearPhoneActionCoordinator(
            authRepository = FakeAuthRepository(authenticatedRider()), remoteTripStore = tripStore, clock = { COORDINATOR_CLOCK },
            manualSosDependencies = WearManualSosDependencies(requester::request, commandStore, now = { STORE_CLOCK }),
        )
        return ManualSosHarness(PhoneWearActionRpcHandler(coordinator, clock = { HANDLER_CLOCK }), tripStore, requester)
    }

    private fun authenticatedRider(): SessionState.Authenticated = SessionState.Authenticated(
        user = AuthUser("rider-1", "rider@example.com", "Rider", "", UserRole.Rider, true),
        accessTokenExpiresAt = Instant.EPOCH,
        rememberMe = true,
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
        private val tripStore: InMemoryRemoteTripSessionStore,
        private val remoteTripId: String,
    ) : ResolvedRemoteTripStarter {
        var calls = 0
        override suspend fun startTrip(): TripMutationResult {
            calls++
            tripStore.setRemoteTripId(remoteTripId)
            return TripMutationResult.Success(remoteTripId, "Active")
        }
    }

    private class FakeMonitoringStarter : MonitoringServiceStarter {
        var calls = 0
        override fun start(): MonitoringServiceStartResult {
            calls++
            return MonitoringServiceStartResult.Started
        }
    }

    private class FakeMonitoringStopper : MonitoringServiceStopper {
        var calls = 0
        override fun stop(): MonitoringServiceStopResult {
            calls++
            return MonitoringServiceStopResult.Stopped
        }
    }

    private class FakeRemoteFinisher(
        private val tripStore: InMemoryRemoteTripSessionStore,
    ) : RemoteTripFinisher {
        var calls = 0
        val tripIds = mutableListOf<String?>()
        override suspend fun finishTrip(request: FinishTripRequestDto): TripMutationResult {
            calls++
            tripIds += tripStore.remoteTripId.value
            val remoteTripId = requireNotNull(tripStore.remoteTripId.value)
            tripStore.clearRemoteTripId()
            return TripMutationResult.Success(remoteTripId, "Finished")
        }
    }

    private class FakeManualSosRequester {
        var calls = 0
        suspend fun request(): LocalIncident { calls++; return LocalIncident(1, 0, 0, 0, 0, IncidentCause.ManualSos, null, RiskLevel.Unknown, 0.0, emptyList(), "test", "test", GpsQualityStatus.Unavailable, false, remoteCreationStatus = IncidentRemoteCreationStatus.Success("incident-1")) }
    }

    private class InMemoryCommandStorage : WearCommandRecordStorage {
        private var records: List<WearCommandRecord> = emptyList()
        override fun read(): List<WearCommandRecord> = records
        override fun write(records: List<WearCommandRecord>) {
            this.records = records
        }
    }

    private data class StartHarness(
        val handler: PhoneWearActionRpcHandler,
        val tripStore: InMemoryRemoteTripSessionStore,
        val remoteStarter: FakeResolvedStarter,
        val monitoringStarter: FakeMonitoringStarter,
    )

    private data class FinishHarness(
        val handler: PhoneWearActionRpcHandler,
        val tripStore: InMemoryRemoteTripSessionStore,
        val stopper: FakeMonitoringStopper,
        val finisher: FakeRemoteFinisher,
        val tripSessionStore: InMemoryTripSessionStore,
    )

    private data class ManualSosHarness(val handler: PhoneWearActionRpcHandler, val tripStore: InMemoryRemoteTripSessionStore, val requester: FakeManualSosRequester)

    private fun <T> WearDecodeResult<T>.success(): T =
        (this as? WearDecodeResult.Success<T>)?.value
            ?: error("Expected Success but was $this")

    private companion object {
        const val COORDINATOR_CLOCK = 1_723_766_400_000L
        const val HANDLER_CLOCK = 1_723_766_400_100L
        const val STORE_CLOCK = 1_723_766_400_200L
    }
}
