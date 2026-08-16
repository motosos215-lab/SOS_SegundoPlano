package com.example.sos_segundoplano.wear

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.wearable.DataMap
import kotlinx.coroutines.awaitCancellation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WearValidationActionGatewayTest {
    @Before
    fun setUp() {
        WearValidationStateStore.resetForTest()
    }

    @After
    fun tearDown() {
        WearValidationStateStore.resetForTest()
    }

    @Test
    fun confirmSafeWithoutActiveCountdownDoesNotSend() = runGatewayTest {
        val transport = CapturingTransport()

        val result = gateway(nodes = listOf(node()), transport = transport).confirmSafe()

        assertEquals(WearValidationActionResult.NoActiveCountdown, result)
        assertEquals(0, transport.calls)
    }

    @Test
    fun confirmSafeWithNonCountdownStateDoesNotSend() = runGatewayTest {
        WearValidationStateStore.confirm(WearDataLayerProtocol.ValidationStatus(state = "safe_confirmed"))
        val transport = CapturingTransport()

        val result = gateway(nodes = listOf(node()), transport = transport).confirmSafe()

        assertEquals(WearValidationActionResult.NoActiveCountdown, result)
        assertEquals(0, transport.calls)
    }

    @Test
    fun countdownWithoutRequiredIdsDoesNotSend() = runGatewayTest {
        WearValidationStateStore.confirm(
            WearDataLayerProtocol.ValidationStatus(state = "countdown_active", remainingMillis = 10_000L)
        )
        val transport = CapturingTransport()

        val result = gateway(nodes = listOf(node()), transport = transport).confirmSafe()

        assertEquals(WearValidationActionResult.NoActiveCountdown, result)
        assertEquals(0, transport.calls)
    }

    @Test
    fun confirmSafePrefersNearbyLowestNodeId() = runGatewayTest {
        confirmCountdown()
        val transport = CapturingTransport()

        val result = gateway(
            nodes = listOf(node("node-z", false), node("node-b", true), node("node-a", true)),
            transport = transport
        ).confirmSafe()

        assertEquals(WearValidationActionResult.Sent, result)
        assertEquals("node-a", transport.nodeId)
    }

    @Test
    fun confirmSafeUsesLowestNodeIdWhenNoneNearby() = runGatewayTest {
        confirmCountdown()
        val transport = CapturingTransport()

        val result = gateway(
            nodes = listOf(node("node-z", false), node("node-b", false), node("node-a", false)),
            transport = transport
        ).confirmSafe()

        assertEquals(WearValidationActionResult.Sent, result)
        assertEquals("node-a", transport.nodeId)
    }

    @Test
    fun confirmSafeWithoutCompanionReturnsCompanionUnavailable() = runGatewayTest {
        val countdown = confirmCountdown()
        val transport = CapturingTransport()

        val result = gateway(nodes = emptyList(), transport = transport).confirmSafe()

        assertEquals(WearValidationActionResult.CompanionUnavailable, result)
        assertEquals(0, transport.calls)
        assertEquals(countdown, WearValidationStateStore.state.value)
    }

    @Test
    fun confirmSafeUsesCorrectPathAndPayload() = runGatewayTest {
        confirmCountdown()
        val transport = CapturingTransport()

        val result = gateway(nodes = listOf(node()), transport = transport).confirmSafe()
        val payload = decodedPayload(transport.payloads.single())

        assertEquals(WearValidationActionResult.Sent, result)
        assertEquals(WearDataLayerProtocol.PATH_VALIDATION_CONFIRM_SAFE, transport.path)
        assertEquals(WearDataLayerProtocol.PROTOCOL_VERSION, payload.getInt("protocolVersion"))
        assertEquals("confirm_safe", payload.getString("action"))
        assertEquals(101L, payload.getLong("sessionId"))
        assertEquals(202L, payload.getLong("assessmentId"))
        assertEquals("wear-101-202-confirm_safe", payload.getString("responseId"))
    }

    @Test
    fun confirmSafeRetryUsesSameResponseId() = runGatewayTest {
        confirmCountdown()
        val transport = CapturingTransport()
        val gateway = gateway(nodes = listOf(node()), transport = transport)

        gateway.confirmSafe()
        gateway.confirmSafe()

        val first = decodedPayload(transport.payloads[0])
        val second = decodedPayload(transport.payloads[1])
        assertEquals(2, transport.calls)
        assertEquals(first.getLong("sessionId"), second.getLong("sessionId"))
        assertEquals(first.getLong("assessmentId"), second.getLong("assessmentId"))
        assertEquals(first.getString("responseId"), second.getString("responseId"))
    }

    @Test
    fun requestHelpUsesCorrectPathAndPayload() = runGatewayTest {
        confirmCountdown()
        val transport = CapturingTransport()

        val result = gateway(nodes = listOf(node()), transport = transport).requestHelp()
        val payload = decodedPayload(transport.payloads.single())

        assertEquals(WearValidationActionResult.Sent, result)
        assertEquals(WearDataLayerProtocol.PATH_VALIDATION_REQUEST_HELP, transport.path)
        assertEquals(WearDataLayerProtocol.PROTOCOL_VERSION, payload.getInt("protocolVersion"))
        assertEquals("request_help", payload.getString("action"))
        assertEquals(101L, payload.getLong("sessionId"))
        assertEquals(202L, payload.getLong("assessmentId"))
        assertEquals("wear-101-202-request_help", payload.getString("responseId"))
    }

    @Test
    fun confirmSafeAndRequestHelpUseDifferentResponseIds() = runGatewayTest {
        confirmCountdown()
        val transport = CapturingTransport()
        val gateway = gateway(nodes = listOf(node()), transport = transport)

        gateway.confirmSafe()
        gateway.requestHelp()

        assertNotEquals(
            decodedPayload(transport.payloads[0]).getString("responseId"),
            decodedPayload(transport.payloads[1]).getString("responseId")
        )
    }

    @Test
    fun confirmSafeSentDoesNotChangeConfirmedCountdown() = runGatewayTest {
        val countdown = confirmCountdown()
        val transport = CapturingTransport()

        val result = gateway(nodes = listOf(node()), transport = transport).confirmSafe()

        assertEquals(WearValidationActionResult.Sent, result)
        assertEquals(countdown, WearValidationStateStore.state.value)
    }

    @Test
    fun requestHelpSentDoesNotChangeConfirmedCountdown() = runGatewayTest {
        val countdown = confirmCountdown()
        val transport = CapturingTransport()

        val result = gateway(nodes = listOf(node()), transport = transport).requestHelp()

        assertEquals(WearValidationActionResult.Sent, result)
        assertEquals(countdown, WearValidationStateStore.state.value)
    }

    @Test
    fun confirmSafeTimeoutPreservesCountdown() = runGatewayTest {
        val countdown = confirmCountdown()
        val transport = CapturingTransport { awaitCancellation() }

        val result = gateway(nodes = listOf(node()), transport = transport, timeoutMillis = 50L).confirmSafe()

        assertEquals(WearValidationActionResult.Timeout, result)
        assertEquals(countdown, WearValidationStateStore.state.value)
    }

    @Test
    fun requestHelpTransportFailurePreservesCountdown() = runGatewayTest {
        val countdown = confirmCountdown()
        val transport = CapturingTransport { throw IllegalStateException("transport failure") }

        val result = gateway(nodes = listOf(node()), transport = transport).requestHelp()

        assertEquals(WearValidationActionResult.TransportFailure, result)
        assertEquals(countdown, WearValidationStateStore.state.value)
    }

    private fun confirmCountdown(): WearDataLayerProtocol.ValidationStatus =
        WearDataLayerProtocol.ValidationStatus(
            state = "countdown_active",
            sessionId = 101L,
            assessmentId = 202L,
            windowId = 303L,
            remainingMillis = 10_000L,
            messageId = "phone-validation-001",
            updatedAtElapsedRealtimeNanos = 404L
        ).also(WearValidationStateStore::confirm)

    private fun gateway(
        nodes: List<CompanionNode>,
        transport: CapturingTransport,
        timeoutMillis: Long = 5_000L
    ) = WearValidationActionGateway(
        context = InstrumentationRegistry.getInstrumentation().targetContext,
        timeoutMillis = timeoutMillis,
        nodeSource = CompanionNodeSource { nodes },
        messageTransport = transport
    )

    private fun node(nodeId: String = "node-a", isNearby: Boolean = true) = CompanionNode(nodeId, isNearby)

    private fun decodedPayload(bytes: ByteArray): DataMap = DataMap.fromByteArray(bytes)

    private fun runGatewayTest(block: suspend () -> Unit) = kotlinx.coroutines.runBlocking { block() }

    private class CapturingTransport(
        private val behavior: suspend () -> Unit = {}
    ) : CompanionMessageTransport {
        var nodeId: String? = null
        var path: String? = null
        var calls: Int = 0
        val payloads = mutableListOf<ByteArray>()

        override suspend fun sendMessage(nodeId: String, path: String, payload: ByteArray) {
            calls += 1
            this.nodeId = nodeId
            this.path = path
            payloads += payload
            behavior()
        }
    }
}
