package com.example.sos_segundoplano.features.wear

import com.example.sos_segundoplano.domain.auth.AccessToken
import com.example.sos_segundoplano.domain.auth.AuthResult
import com.example.sos_segundoplano.domain.auth.AuthUser
import com.example.sos_segundoplano.domain.auth.SessionExpired
import com.example.sos_segundoplano.domain.auth.SessionState
import com.example.sos_segundoplano.domain.auth.UserRole
import com.example.sos_segundoplano.domain.repository.AuthRepository
import com.example.sos_segundoplano.domain.wear.HeartRatePermissionState
import com.example.sos_segundoplano.domain.wear.VectorReading
import com.example.sos_segundoplano.domain.wear.WatchConnectionRepository
import com.example.sos_segundoplano.domain.wear.WatchConnectionState
import com.example.sos_segundoplano.domain.wear.WatchDeclineReason
import com.example.sos_segundoplano.domain.wear.WatchDeviceSummary
import com.example.sos_segundoplano.domain.wear.WatchHandshakeInfo
import com.example.sos_segundoplano.domain.wear.WatchRequestResult
import com.example.sos_segundoplano.domain.wear.WatchSensorSnapshot
import com.example.sos_segundoplano.domain.wear.WatchStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class WatchConnectionViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun onScreenStartedRequestsRefresh() = runTest {
        val repo = FakeWatchRepository()
        val vm = vm(repo)

        vm.onScreenStarted()

        assertEquals(1, repo.refreshCalls)
    }

    @Test fun onScreenStartedRefreshFailureShowsDataLayerUnavailable() = runTest {
        val repo = FakeWatchRepository().apply { refreshFailure = RuntimeException("wear unavailable") }
        val vm = vm(repo)

        vm.onScreenStarted()

        assertEquals(WatchConnectionState.DataLayerUnavailable, vm.uiState.value.connection)
    }

    @Test fun capabilityChangeRequestsRefreshWhileScreenIsActive() = runTest {
        val repo = FakeWatchRepository()
        val vm = vm(repo)
        vm.onScreenStarted()

        repo.emitConnectionChange()

        assertEquals(2, repo.refreshCalls)
    }

    @Test fun capabilityChangeRefreshFailureShowsDataLayerUnavailable() = runTest {
        val repo = FakeWatchRepository()
        val vm = vm(repo)
        vm.onScreenStarted()
        repo.refreshFailure = RuntimeException("wear unavailable")

        repo.emitConnectionChange()

        assertEquals(WatchConnectionState.DataLayerUnavailable, vm.uiState.value.connection)
    }

    @Test fun connectionEmittedIsReflectedInState() = runTest {
        val repo = FakeWatchRepository()
        val vm = vm(repo)

        repo.emit(connectedState())

        when (val connection = vm.uiState.value.connection) {
            is WatchConnectionState.Connected -> assertEquals("Watch", connection.device.displayName)
            else -> throw AssertionError("Expected Connected but was $connection")
        }
    }

    @Test fun connectedKeepsHandshakeInfo() = runTest {
        val repo = FakeWatchRepository()
        val vm = vm(repo)

        repo.emit(connectedState())

        assertTrue(vm.uiState.value.handshake != null)
        assertEquals("Model-X", vm.uiState.value.handshake!!.model)
    }

    @Test fun disconnectedClearsStaleReadingsAndHandshake() = runTest {
        val repo = FakeWatchRepository(initial = connectedState())
        val vm = vm(repo)
        repo.statusResult = WatchRequestResult.Success(Sample.status())
        vm.requestStatus()
        assertTrue(vm.uiState.value.status != null)

        repo.emit(WatchConnectionState.Disconnected)

        assertNull(vm.uiState.value.status)
        assertNull(vm.uiState.value.handshake)
    }

    @Test fun requestStatusWhenConnectedSurfacesReadings() = runTest {
        val repo = FakeWatchRepository(initial = connectedState())
        val vm = vm(repo)
        repo.statusResult = WatchRequestResult.Success(Sample.status())

        vm.requestStatus()

        assertEquals(72, vm.uiState.value.status?.batteryPercent)
        assertEquals(false, vm.uiState.value.isRequestingStatus)
        assertNull(vm.uiState.value.notice)
    }

    @Test fun requestSnapshotWhenConnectedSurfacesSnapshot() = runTest {
        val repo = FakeWatchRepository(initial = connectedState())
        val vm = vm(repo)
        repo.snapshotResult = WatchRequestResult.Success(Sample.snapshot())

        vm.requestSnapshot()

        assertEquals(85f, vm.uiState.value.snapshot!!.heartRateBpm)
        assertEquals(1.0f, vm.uiState.value.snapshot!!.accelerometer!!.x)
        assertEquals(false, vm.uiState.value.isRequestingSnapshot)
    }

    @Test fun requestStatusDeclinedSurfacesNoticeAndClearsBusy() = runTest {
        val repo = FakeWatchRepository(initial = connectedState())
        val vm = vm(repo)
        repo.statusResult = WatchRequestResult.Declined(WatchDeclineReason.PermissionDenied)

        vm.requestStatus()

        assertEquals(WatchNotice.Declined(WatchDeclineReason.PermissionDenied), vm.uiState.value.notice)
        assertEquals(false, vm.uiState.value.isRequestingStatus)
    }

    @Test fun requestSnapshotTimeoutSurfacesNotice() = runTest {
        val repo = FakeWatchRepository(initial = connectedState())
        val vm = vm(repo)
        repo.snapshotResult = WatchRequestResult.Timeout

        vm.requestSnapshot()

        assertEquals(WatchNotice.Timeout(WatchOperation.Snapshot), vm.uiState.value.notice)
        assertEquals(false, vm.uiState.value.isRequestingSnapshot)
    }

    @Test fun notConnectedResultIsSilentAndClearsBusy() = runTest {
        val repo = FakeWatchRepository(initial = connectedState())
        val vm = vm(repo)
        repo.statusResult = WatchRequestResult.NotConnected

        vm.requestStatus()

        assertNull(vm.uiState.value.notice)
        assertEquals(false, vm.uiState.value.isRequestingStatus)
    }

    @Test fun staleResponseFromPreviousSessionIsDiscarded() = runTest {
        val auth = FakeAuthRepository()
        val repo = FakeWatchRepository(initial = connectedState())
        val vm = vm(repo, auth)
        repo.statusGate = CompletableDeferred()
        repo.statusResult = WatchRequestResult.Success(Sample.status())

        vm.requestStatus()
        auth.emit(SessionState.LoggedOut)
        repo.statusGate!!.complete(Unit)

        assertNull(vm.uiState.value.status)
        assertNull(vm.uiState.value.notice)
        assertEquals(false, vm.uiState.value.isRequestingStatus)
    }

    @Test fun logoutResetsRepositoryAndCleansAllState() = runTest {
        val auth = FakeAuthRepository()
        val repo = FakeWatchRepository(initial = connectedState())
        val vm = vm(repo, auth)
        repo.statusResult = WatchRequestResult.Success(Sample.status())
        vm.requestStatus()
        assertTrue(vm.uiState.value.status != null)

        auth.emit(SessionState.LoggedOut)

        assertEquals(1, repo.resetCalls)
        val state = vm.uiState.value
        assertEquals(WatchConnectionState.Disconnected, state.connection)
        assertNull(state.handshake)
        assertNull(state.status)
        assertNull(state.snapshot)
        assertNull(state.notice)
        assertEquals(false, state.isRequestingStatus)
        assertEquals(false, state.isRequestingSnapshot)
    }

    @Test fun refreshAfterTimeoutRestoresConnectivity() = runTest {
        val repo = FakeWatchRepository(initial = connectedState())
        val vm = vm(repo)
        vm.onScreenStarted()
        vm.requestSnapshot()
        repo.emit(WatchConnectionState.Timeout(connectedState().device))
        repo.refreshState = connectedState()

        vm.refreshConnection()

        assertEquals(2, repo.refreshCalls)
        when (val connection = vm.uiState.value.connection) {
            is WatchConnectionState.Connected -> assertEquals("Watch", connection.device.displayName)
            else -> throw AssertionError("Expected Connected but was $connection")
        }
    }

    @Test fun factoryCreatesViewModel() {
        val repo = FakeWatchRepository()
        val factory = WatchConnectionViewModel.Factory(repo, FakeAuthRepository())
        val vm = factory.create(WatchConnectionViewModel::class.java)

        assertEquals(WatchConnectionViewModel::class.java, vm.javaClass)
    }

    // -------------------------------------------------------------- helpers

    private fun vm(
        repo: WatchConnectionRepository,
        auth: AuthRepository = FakeAuthRepository()
    ) = WatchConnectionViewModel(repo, auth)

    private fun connectedState() = WatchConnectionState.Connected(
        device = WatchDeviceSummary("Watch", true),
        handshake = WatchHandshakeInfo(
            appVersionName = "motosos-wear",
            appVersionCode = 12L,
            manufacturer = "Maker",
            model = "Model-X",
            wearOsApiLevel = 34
        )
    )
}

private object Sample {
    fun status() = WatchStatus(
        batteryAvailable = true,
        batteryPercent = 72,
        chargingKnown = true,
        charging = false,
        accelerometerAvailable = true,
        gyroscopeAvailable = false,
        heartRateAvailable = true,
        heartRatePermission = HeartRatePermissionState.Granted,
        respondedAtEpochMs = 1_700_000_000_000L
    )

    fun snapshot() = WatchSensorSnapshot(
        capturedAtEpochMs = 1_700_000_000_000L,
        accelerometerAvailable = true,
        accelerometer = VectorReading(1.0f, 2.0f, 3.0f),
        gyroscopeAvailable = false,
        gyroscope = null,
        heartRateAvailable = true,
        heartRatePermission = HeartRatePermissionState.Granted,
        heartRateBpm = 85f
    )
}

private class FakeWatchRepository(
    initial: WatchConnectionState = WatchConnectionState.Loading
) : WatchConnectionRepository {
    private val flow = MutableStateFlow(initial)
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val connectionState: StateFlow<WatchConnectionState> = flow
    override val connectionChanges: Flow<Unit> = changes

    var refreshCalls = 0
    var resetCalls = 0
    var refreshState: WatchConnectionState? = null
    var refreshFailure: RuntimeException? = null
    var statusGate: CompletableDeferred<Unit>? = null
    var snapshotGate: CompletableDeferred<Unit>? = null
    var statusResult: WatchRequestResult<WatchStatus> = WatchRequestResult.InvalidResponse
    var snapshotResult: WatchRequestResult<WatchSensorSnapshot> = WatchRequestResult.InvalidResponse

    override suspend fun refreshConnection() {
        refreshCalls++
        refreshFailure?.let { throw it }
        refreshState?.let { flow.value = it }
    }

    override suspend fun requestStatus(): WatchRequestResult<WatchStatus> {
        statusGate?.await()
        return statusResult
    }

    override suspend fun requestSnapshot(): WatchRequestResult<WatchSensorSnapshot> {
        snapshotGate?.await()
        return snapshotResult
    }

    override fun reset() {
        resetCalls++
        flow.value = WatchConnectionState.Disconnected
    }

    fun emit(state: WatchConnectionState) {
        flow.value = state
    }

    fun emitConnectionChange() {
        changes.tryEmit(Unit)
    }
}

private class FakeAuthRepository(
    initial: SessionState = SessionState.Authenticated(
        user = AuthUser("u1", "r@e.com", "Rider", "+521", UserRole.Rider, true),
        accessTokenExpiresAt = Instant.parse("2026-08-06T12:00:00Z"),
        rememberMe = true
    )
) : AuthRepository {
    private val flow = MutableStateFlow(initial)
    override fun observeSession(): StateFlow<SessionState> = flow
    fun emit(state: SessionState) {
        flow.value = state
    }

    override suspend fun login(email: String, password: String, rememberMe: Boolean): AuthResult<AuthUser> =
        throw AssertionError("Unexpected login call")
    override suspend fun restoreSession(): AuthResult<AuthUser?> = AuthResult.Success(null)
    override suspend fun ensureValidAccessToken(): AuthResult<AccessToken> = SessionExpired
    override suspend fun refreshSession(): AuthResult<AuthUser> = throw AssertionError("Unexpected refreshSession call")
    override suspend fun logout(): AuthResult<Unit> {
        flow.value = SessionState.LoggedOut
        return AuthResult.Success(Unit)
    }
}
