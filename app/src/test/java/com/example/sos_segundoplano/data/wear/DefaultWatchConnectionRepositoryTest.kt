package com.example.sos_segundoplano.data.wear

import com.example.sos_segundoplano.domain.auth.AccessToken
import com.example.sos_segundoplano.domain.auth.AuthResult
import com.example.sos_segundoplano.domain.auth.AuthUser
import com.example.sos_segundoplano.domain.auth.SessionExpired
import com.example.sos_segundoplano.domain.auth.SessionState
import com.example.sos_segundoplano.domain.auth.UserRole
import com.example.sos_segundoplano.domain.repository.AuthRepository
import com.example.sos_segundoplano.domain.wear.WatchConnectionErrorCode
import com.example.sos_segundoplano.domain.wear.WatchConnectionState
import com.example.sos_segundoplano.domain.wear.WatchDeclineReason
import com.example.sos_segundoplano.domain.wear.WatchDeviceSummary
import com.example.sos_segundoplano.domain.wear.WatchRequestResult
import com.example.sos_segundoplano.wearprotocol.HandshakeRequest
import com.example.sos_segundoplano.wearprotocol.HandshakeResponse
import com.example.sos_segundoplano.wearprotocol.SensorPermissionState
import com.example.sos_segundoplano.wearprotocol.WatchSnapshotRequest
import com.example.sos_segundoplano.wearprotocol.WatchSnapshotResponse
import com.example.sos_segundoplano.wearprotocol.WatchStatusRequest
import com.example.sos_segundoplano.wearprotocol.WatchStatusResponse
import com.example.sos_segundoplano.wearprotocol.WearDecodeResult
import com.example.sos_segundoplano.wearprotocol.WearProtocol
import com.example.sos_segundoplano.wearprotocol.WearProtocolError
import com.example.sos_segundoplano.wearprotocol.WearRpcResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DefaultWatchConnectionRepositoryTest {

    @Test fun refreshWithoutSessionFlipsToDisconnectedAndDoesNotTouchGateway() = runTest {
        val gateway = FakeWearGateway()
        val auth = FakeAuthRepository(SessionState.LoggedOut)
        val repo = repo(gateway, auth)

        repo.refreshConnection()

        assertEquals(WatchConnectionState.Disconnected, repo.connectionState.value)
        assertEquals(0, gateway.stateCalls)
    }

    @Test fun refreshWhenApiUnavailableReportsDataLayerUnavailable() = runTest {
        val gateway = FakeWearGateway().apply { wearApiAvailable = false }
        val repo = repo(gateway)

        repo.refreshConnection()

        assertEquals(WatchConnectionState.DataLayerUnavailable, repo.connectionState.value)
    }

    @Test fun refreshWhenWearableApiTaskFailsWithApiUnavailableReportsDataLayerUnavailable() = runTest {
        val gateway = FakeWearGateway().apply { connectedNodesFailure = WearApiException.apiUnavailable() }
        val repo = repo(gateway)

        repo.refreshConnection()

        assertEquals(WatchConnectionState.DataLayerUnavailable, repo.connectionState.value)
    }

    @Test fun refreshWithoutConnectedNodesReportsNoWearNodes() = runTest {
        val gateway = FakeWearGateway().apply {
            connectedNodes = emptyList()
            capableNodes = emptyList()
        }
        val repo = repo(gateway)

        repo.refreshConnection()

        assertEquals(WatchConnectionState.NoWearNodes, repo.connectionState.value)
    }

    @Test fun refreshWithWatchButNotMotoSosReportsMissingApp() = runTest {
        val gateway = FakeWearGateway().apply { capableNodes = emptyList() }
        val repo = repo(gateway)

        repo.refreshConnection()

        assertEquals(WatchConnectionState.WearNodeDetectedWithoutMotoSos, repo.connectionState.value)
    }

    @Test fun refreshWithCapableNodeIndirectShowsIndirectAndDoesNotRpc() = runTest {
        val gateway = FakeWearGateway(nearby = false)
        val repo = repo(gateway)

        repo.refreshConnection()

        assertEquals(
            WatchConnectionState.CompatibleNodeIndirect(WATCH_DEVICE.copy(isNearby = false)),
            repo.connectionState.value
        )
        assertEquals(0, gateway.rpcCalls)
    }

    @Test fun refreshPrefersNearbyNodeOverIndirect() = runTest {
        val far = WearNodeInfo("node-far", "Far", isNearby = false)
        val near = WearNodeInfo("node-near", "Near", isNearby = true)
        val gateway = FakeWearGateway(
            connected = listOf(far, near),
            capable = listOf(far, near)
        )
        gateway.handshakeResponse = happyHandshake()
        val repo = repo(gateway)

        repo.refreshConnection()

        assertEquals("node-near", gateway.lastRpcNodeId)
        val state = repo.connectionState.value
        assertTrue(state is WatchConnectionState.Connected)
        assertEquals("Near", (state as WatchConnectionState.Connected).device.displayName)
    }

    @Test fun refreshSuccessfulHandshakeTransitionsToConnected() = runTest {
        val gateway = FakeWearGateway()
        gateway.handshakeResponse = happyHandshake()
        val repo = repo(gateway)

        repo.refreshConnection()

        val state = repo.connectionState.value
        assertTrue(state is WatchConnectionState.Connected)
        val connected = state as WatchConnectionState.Connected
        assertEquals(WATCH_DEVICE, connected.device)
        assertEquals("motosos-wear", connected.handshake.appVersionName)
        assertEquals(12L, connected.handshake.appVersionCode)
        assertEquals("Maker", connected.handshake.manufacturer)
        assertEquals("Model-X", connected.handshake.model)
        assertEquals(34, connected.handshake.wearOsApiLevel)
    }

    @Test fun refreshRpcFailureReportsSendFailed() = runTest {
        val gateway = FakeWearGateway().apply {
            handshakeSend = WearMessageResult.Failure("boom")
        }
        val repo = repo(gateway)

        repo.refreshConnection()

        assertEquals(
            WatchConnectionState.Error(WatchConnectionErrorCode.SendFailed, WATCH_DEVICE),
            repo.connectionState.value
        )
    }

    @Test fun refreshHandshakeTimeoutReportsTimeout() = runTest {
        val gateway = FakeWearGateway(hangHandshake = true)
        val repo = repo(gateway, timeoutMillis = 1_000L)

        repo.refreshConnection()

        assertEquals(WatchConnectionState.Timeout(WATCH_DEVICE), repo.connectionState.value)
    }

    @Test fun refreshMalformedHandshakeReportsProtocolIncompatible() = runTest {
        val gateway = FakeWearGateway().apply { handshakeDecode = decodeFailure() }
        val repo = repo(gateway)

        repo.refreshConnection()

        assertEquals(WatchConnectionState.ProtocolIncompatible(WATCH_DEVICE), repo.connectionState.value)
    }

    @Test fun refreshCorrelationMismatchReportsProtocolIncompatible() = runTest {
        val gateway = FakeWearGateway().apply {
            handshakeResponse = happyHandshake(requestId = "other-req")
        }
        val repo = repo(gateway)

        repo.refreshConnection()

        assertEquals(WatchConnectionState.ProtocolIncompatible(WATCH_DEVICE), repo.connectionState.value)
    }

    @Test fun refreshNonOkResultIsProtocolIncompatible() = runTest {
        val gateway = FakeWearGateway().apply {
            handshakeResponse = happyHandshake(result = WearRpcResult.NOT_READY)
        }
        val repo = repo(gateway)

        repo.refreshConnection()

        assertEquals(WatchConnectionState.ProtocolIncompatible(WATCH_DEVICE), repo.connectionState.value)
    }

    // ------------------------------------------------------------- status RPC

    @Test fun requestStatusWithoutConnectionReturnsNotConnected() = runTest {
        val gateway = FakeWearGateway()
        val repo = repo(gateway)

        val result = repo.requestStatus()

        assertEquals(WatchRequestResult.NotConnected, result)
        assertEquals(0, gateway.rpcCalls)
    }

    @Test fun requestStatusAfterConnectedReturnsSuccess() = runTest {
        val rich = connectedRepo()
        rich.gateway.statusResponse = happyStatus()

        val result = rich.repo.requestStatus()

        assertTrue(result is WatchRequestResult.Success)
        val status = (result as WatchRequestResult.Success).value
        assertEquals(78, status.batteryPercent)
        assertTrue(status.batteryAvailable)
        assertTrue(status.chargingKnown)
        assertEquals(true, status.charging)
        assertTrue(rich.repo.connectionState.value is WatchConnectionState.Connected)
    }

    @Test fun requestStatusAlreadyRequestingReturnsNotConnected() = runTest(UnconfinedTestDispatcher()) {
        val rich = connectedRepo(timeoutMillis = 1_000L)
        rich.gateway.statusGate = Portal()
        rich.gateway.statusResponse = happyStatus()

        val pending = async { rich.repo.requestStatus() }
        val second = rich.repo.requestStatus()

        assertEquals(WatchRequestResult.NotConnected, second)

        rich.gateway.statusGate!!.open()
        pending.await()
    }

    @Test fun requestStatusTimeoutReturnsTimeoutAndStateTimesOut() = runTest {
        val rich = connectedRepo()
        rich.gateway.statusGate = Portal() // nunca se abre

        val result = rich.repo.requestStatus()

        assertEquals(WatchRequestResult.Timeout, result)
        assertTrue(rich.repo.connectionState.value is WatchConnectionState.Timeout)
    }

    @Test fun requestStatusInvalidResponseReturnsInvalidAndReconnects() = runTest {
        val rich = connectedRepo()
        rich.gateway.statusDecode = decodeFailure()

        val result = rich.repo.requestStatus()

        assertEquals(WatchRequestResult.InvalidResponse, result)
        assertTrue(rich.repo.connectionState.value is WatchConnectionState.Connected)
    }

    @Test fun requestStatusResponseWithWrongCorrelationIsInvalid() = runTest {
        val rich = connectedRepo()
        rich.gateway.statusResponse = happyStatus(requestId = "wrong-request")

        val result = rich.repo.requestStatus()

        assertEquals(WatchRequestResult.InvalidResponse, result)
    }

    @Test fun requestStatusDeclinedReasonIsMapped() = runTest {
        val rich = connectedRepo()
        rich.gateway.statusResponse = happyStatus(result = WearRpcResult.SENSOR_UNAVAILABLE)

        val result = rich.repo.requestStatus()

        assertEquals(WatchRequestResult.Declined(WatchDeclineReason.SensorUnavailable), result)
    }

    @Test fun requestStatusTimeoutThenRefreshReturnsToConnected() = runTest {
        val rich = connectedRepo()
        rich.gateway.statusGate = Portal()

        assertEquals(WatchRequestResult.Timeout, rich.repo.requestStatus())

        rich.gateway.statusGate = null
        rich.repo.refreshConnection()

        assertTrue(rich.repo.connectionState.value is WatchConnectionState.Connected)
    }

    // ------------------------------------------------------------ snapshot RPC

    @Test fun requestSnapshotAfterConnectedReturnsReading() = runTest {
        val rich = connectedRepo()
        rich.gateway.snapshotResponse = happySnapshot()

        val result = rich.repo.requestSnapshot()

        assertTrue(result is WatchRequestResult.Success)
        val snap = (result as WatchRequestResult.Success).value
        assertEquals(1.0f, snap.accelerometer!!.x)
        assertEquals(85f, snap.heartRateBpm!!)
        assertTrue(snap.accelerometerAvailable)
    }

    @Test fun requestSnapshotTimeoutCollapsesState() = runTest {
        val rich = connectedRepo()
        rich.gateway.snapshotGate = Portal()

        val result = rich.repo.requestSnapshot()

        assertEquals(WatchRequestResult.Timeout, result)
        assertTrue(rich.repo.connectionState.value is WatchConnectionState.Timeout)
    }

    @Test fun requestSnapshotInvalidDecodeReturnsInvalidAndReconnects() = runTest {
        val rich = connectedRepo()
        rich.gateway.snapshotDecode = decodeFailure()

        val result = rich.repo.requestSnapshot()

        assertEquals(WatchRequestResult.InvalidResponse, result)
        assertTrue(rich.repo.connectionState.value is WatchConnectionState.Connected)
    }

    // ----------------------------------------------------------------- lifecycle

    @Test fun logoutFlipsToDisconnectedAndBlocksRequests() = runTest {
        val rich = connectedRepo()
        rich.auth.emit(SessionState.LoggedOut)

        rich.repo.refreshConnection()
        assertEquals(WatchConnectionState.Disconnected, rich.repo.connectionState.value)

        val result = rich.repo.requestStatus()
        assertEquals(WatchRequestResult.NotConnected, result)
    }

    @Test fun resetLeavesDisconnectedAndBlocksRequests() = runTest {
        val rich = connectedRepo()

        rich.repo.reset()
        assertEquals(WatchConnectionState.Disconnected, rich.repo.connectionState.value)

        val result = rich.repo.requestSnapshot()
        assertEquals(WatchRequestResult.NotConnected, result)
    }

    @Test fun connectedStateExposesDeviceMetaNotRawNodeIdText() = runTest {
        val gateway = FakeWearGateway()
        gateway.handshakeResponse = happyHandshake()
        val repo = repo(gateway)

        repo.refreshConnection()
        val state = repo.connectionState.value
        assertTrue(state is WatchConnectionState.Connected)
        // La pantalla solo muestra displayName y datos del handshake; el nodeId queda interno.
        assertEquals("Watch", (state as WatchConnectionState.Connected).device.displayName)
    }

    // --------------------------------------------------------------- helpers

    private fun repo(
        gateway: FakeWearGateway,
        auth: FakeAuthRepository = FakeAuthRepository(),
        timeoutMillis: Long = 5_000L
    ): DefaultWatchConnectionRepository = DefaultWatchConnectionRepository(
        authRepository = auth,
        gateway = gateway,
        transport = FakeTransport(gateway),
        requestIdGenerator = { "req-1" },
        clock = { CLOCK },
        timeoutMillis = timeoutMillis
    )

    private suspend fun connectedRepo(timeoutMillis: Long = 5_000L): ConnectedWatch {
        val auth = FakeAuthRepository()
        val gateway = FakeWearGateway()
        gateway.handshakeResponse = happyHandshake()
        val repo = repo(gateway, auth, timeoutMillis)
        repo.refreshConnection()
        check(repo.connectionState.value is WatchConnectionState.Connected)
        return ConnectedWatch(auth, gateway, repo)
    }

    private class ConnectedWatch(
        val auth: FakeAuthRepository,
        val gateway: FakeWearGateway,
        val repo: DefaultWatchConnectionRepository
    )
}

// ------------------------------------------------------------------ builders

private object Fixtures {
    const val CLOCK: Long = 1_700_000_000_000L
    val WATCH_DEVICE = WatchDeviceSummary("Watch", true)

    fun happyHandshake(
        requestId: String = "req-1",
        result: WearRpcResult = WearRpcResult.OK
    ) = HandshakeResponse(
        protocolVersion = WearProtocol.PROTOCOL_VERSION,
        schemaVersion = WearProtocol.SCHEMA_VERSION,
        requestId = requestId,
        result = result,
        appVersionName = "motosos-wear",
        appVersionCode = 12L,
        manufacturer = "Maker",
        model = "Model-X",
        wearOsApiLevel = 34,
        capability = WearProtocol.CAPABILITY_WEAR_TELEMETRY,
        respondedAtEpochMs = CLOCK
    )

    fun happyStatus(
        requestId: String = "req-1",
        result: WearRpcResult = WearRpcResult.OK
    ) = WatchStatusResponse(
        protocolVersion = WearProtocol.PROTOCOL_VERSION,
        schemaVersion = WearProtocol.SCHEMA_VERSION,
        requestId = requestId,
        result = result,
        sequence = 1L,
        batteryAvailable = true,
        batteryPercent = 78,
        chargingKnown = true,
        charging = true,
        accelerometerAvailable = true,
        gyroscopeAvailable = false,
        heartRateAvailable = true,
        heartRatePermission = SensorPermissionState.GRANTED,
        respondedAtEpochMs = CLOCK
    )

    fun happySnapshot(requestId: String = "req-1") = WatchSnapshotResponse(
        protocolVersion = WearProtocol.PROTOCOL_VERSION,
        schemaVersion = WearProtocol.SCHEMA_VERSION,
        requestId = requestId,
        result = WearRpcResult.OK,
        sequence = 1L,
        capturedAtEpochMs = CLOCK,
        accelerometerAvailable = true,
        accelerometerX = 1.0f,
        accelerometerY = 2.0f,
        accelerometerZ = 3.0f,
        gyroscopeAvailable = true,
        gyroscopeX = 0.1f,
        gyroscopeY = 0.2f,
        gyroscopeZ = 0.3f,
        heartRateAvailable = true,
        heartRatePermission = SensorPermissionState.GRANTED,
        heartRateBpmPresent = true,
        heartRateBpm = 85f
    )

    fun <T> decodeFailure(): WearDecodeResult<T> =
        WearDecodeResult.Failure(WearProtocolError.CorruptPayload)
}

private val WATCH_DEVICE: WatchDeviceSummary get() = WatchDeviceSummary("Watch", true)
private const val CLOCK: Long = 1_700_000_000_000L
private fun happyHandshake(requestId: String = "req-1", result: WearRpcResult = WearRpcResult.OK) =
    Fixtures.happyHandshake(requestId, result)
private fun happyStatus(requestId: String = "req-1", result: WearRpcResult = WearRpcResult.OK) =
    Fixtures.happyStatus(requestId, result)
private fun happySnapshot(requestId: String = "req-1") = Fixtures.happySnapshot(requestId)
private fun <T> decodeFailure(): WearDecodeResult<T> = Fixtures.decodeFailure()

// ------------------------------------------------------------------ fakes

private class FakeAuthRepository(initial: SessionState = defaultSession()) : AuthRepository {
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

private fun defaultSession(): SessionState = SessionState.Authenticated(
    user = AuthUser(
        id = "u1",
        email = "r@e.com",
        fullName = "Rider",
        phoneNumber = "+52155",
        role = UserRole.Rider,
        isActive = true
    ),
    accessTokenExpiresAt = Instant.parse("2026-08-06T12:00:00Z"),
    rememberMe = true
)

private class FakeWearGateway(
    connected: List<WearNodeInfo> = listOf(WATCH_DEVICE_INFO),
    capable: List<WearNodeInfo> = listOf(WATCH_DEVICE_INFO),
    private val nearby: Boolean = true,
    private val hangHandshake: Boolean = false
) : WearPlatformGateway {
    var wearApiAvailable = true
    var connectedNodes: List<WearNodeInfo> = connected
    var capableNodes: List<WearNodeInfo> = capable
    var connectedNodesFailure: RuntimeException? = null
    var capableNodesFailure: RuntimeException? = null

    var handshakeSend: WearMessageResult = WearMessageResult.Success(byteArrayOf(1))
    var handshakeResponse: HandshakeResponse? = null
    var handshakeDecode: WearDecodeResult<HandshakeResponse>? = null

    var statusResponse: WatchStatusResponse? = null
    var statusDecode: WearDecodeResult<WatchStatusResponse>? = null
    var statusGate: Portal? = null

    var snapshotResponse: WatchSnapshotResponse? = null
    var snapshotDecode: WearDecodeResult<WatchSnapshotResponse>? = null
    var snapshotGate: Portal? = null

    var stateCalls = 0
    var rpcCalls = 0
    var lastRpcNodeId: String? = null

    override fun isWearableApiAvailable(): Boolean = wearApiAvailable

    override suspend fun fetchConnectedNodes(): List<WearNodeInfo> {
        stateCalls++
        connectedNodesFailure?.let { throw it }
        return connectedNodes.map { if (nearby) it else it.copy(isNearby = false) }
    }

    override suspend fun fetchCapabilityNodes(capability: String): List<WearNodeInfo> {
        stateCalls++
        capableNodesFailure?.let { throw it }
        return capableNodes.map { if (nearby) it else it.copy(isNearby = false) }
    }

    override suspend fun sendMessageRequest(nodeId: String, path: String, payload: ByteArray): WearMessageResult {
        rpcCalls++
        lastRpcNodeId = nodeId
        return when (path) {
            WearProtocol.PATH_HANDSHAKE -> handshakeResult()
            WearProtocol.PATH_STATUS -> statusResult()
            else -> snapshotResult()
        }
    }

    override fun observeCapabilityChanges(capability: String): Flow<Unit> = emptyFlow()

    private suspend fun handshakeResult(): WearMessageResult {
        if (hangHandshake) suspendCancellableCoroutine<Unit> { }
        if (handshakeDecode != null || handshakeResponse != null) return WearMessageResult.Success(byteArrayOf(2))
        return handshakeSend
    }

    private suspend fun statusResult(): WearMessageResult {
        statusGate?.await()
        return WearMessageResult.Success(byteArrayOf(3))
    }

    private suspend fun snapshotResult(): WearMessageResult {
        snapshotGate?.await()
        return WearMessageResult.Success(byteArrayOf(4))
    }
}

private class FakeTransport(private val delegate: FakeWearGateway) : WatchProtocolTransport {
    override fun encodeHandshakeRequest(request: HandshakeRequest): ByteArray = byteArrayOf(1)
    override fun encodeStatusRequest(request: WatchStatusRequest): ByteArray = byteArrayOf(1)
    override fun encodeSnapshotRequest(request: WatchSnapshotRequest): ByteArray = byteArrayOf(1)

    override fun decodeHandshakeResponse(bytes: ByteArray): WearDecodeResult<HandshakeResponse> {
        delegate.handshakeDecode?.let { return it }
        val response = delegate.handshakeResponse ?: return WearDecodeResult.Failure(WearProtocolError.CorruptPayload)
        return WearDecodeResult.Success(response)
    }

    override fun decodeStatusResponse(bytes: ByteArray): WearDecodeResult<WatchStatusResponse> {
        delegate.statusDecode?.let { return it }
        val response = delegate.statusResponse ?: return WearDecodeResult.Failure(WearProtocolError.CorruptPayload)
        return WearDecodeResult.Success(response)
    }

    override fun decodeSnapshotResponse(bytes: ByteArray): WearDecodeResult<WatchSnapshotResponse> {
        delegate.snapshotDecode?.let { return it }
        val response = delegate.snapshotResponse ?: return WearDecodeResult.Failure(WearProtocolError.CorruptPayload)
        return WearDecodeResult.Success(response)
    }
}

private val WATCH_DEVICE_INFO = WearNodeInfo("node-1", "Watch", isNearby = true)

/**
 * Compuerta de suspensión: [await] se suspende hasta que [open] sea llamado. Permite simular
 * respuestas lentas o nunca llegadas en determinadas pruebas.
 */
private class Portal {
    private val deferred = CompletableDeferred<Unit>()
    suspend fun await() {
        deferred.await()
    }
    fun open() {
        deferred.complete(Unit)
    }
}
