package com.example.sos_segundoplano.data.wear

import com.example.sos_segundoplano.domain.auth.SessionState
import com.example.sos_segundoplano.domain.repository.AuthRepository
import com.example.sos_segundoplano.domain.wear.WatchConnectionErrorCode
import com.example.sos_segundoplano.domain.wear.WatchConnectionRepository
import com.example.sos_segundoplano.domain.wear.WatchConnectionState
import com.example.sos_segundoplano.domain.wear.WatchDeclineReason
import com.example.sos_segundoplano.domain.wear.WatchDeviceSummary
import com.example.sos_segundoplano.domain.wear.WatchHandshakeInfo
import com.example.sos_segundoplano.domain.wear.WatchRequestResult
import com.example.sos_segundoplano.domain.wear.WatchSensorSnapshot
import com.example.sos_segundoplano.domain.wear.WatchStatus
import com.example.sos_segundoplano.wearprotocol.HandshakeRequest
import com.example.sos_segundoplano.wearprotocol.WatchSnapshotRequest
import com.example.sos_segundoplano.wearprotocol.WatchStatusRequest
import com.example.sos_segundoplano.wearprotocol.WearDecodeResult
import com.example.sos_segundoplano.wearprotocol.WearProtocol
import com.example.sos_segundoplano.wearprotocol.WearRequestIdGenerator
import com.example.sos_segundoplano.wearprotocol.WearRpcResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout

/** Límite de espera de una respuesta del reloj. Menor que la espera infinita de la API. */
const val WEAR_RPC_TIMEOUT_MILLIS: Long = 10_000L

/**
 * Implementación del repositorio de conexión con el reloj.
 *
 * - Busca nodos con la capability `motosos_wear_telemetry_v1` y selecciona uno de forma
 *   determinista (cercano primero, orden estable por `nodeId`).
 * - Hace el handshake automáticamente contra un nodo cercano compatible.
 * - Las solicitudes de status/snapshot requieren un nodo conectado y se correlacionan por
 *   `requestId`; las respuestas no correspondientes se descartan.
 * - Cada operación captura la "era" de sesión; un logout o una nueva comprobación descartan
 *   las respuestas obsoletas.
 */
class DefaultWatchConnectionRepository(
    private val authRepository: AuthRepository,
    private val gateway: WearPlatformGateway,
    private val transport: WatchProtocolTransport = WearProtocolCodecTransport,
    private val requestIdGenerator: () -> String = WearRequestIdGenerator::newRequestId,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeoutMillis: Long = WEAR_RPC_TIMEOUT_MILLIS
) : WatchConnectionRepository {

    private val mutableState = MutableStateFlow<WatchConnectionState>(WatchConnectionState.Loading)
    override val connectionState: StateFlow<WatchConnectionState> = mutableState.asStateFlow()
    override val connectionChanges: Flow<Unit> =
        gateway.observeCapabilityChanges(WearProtocol.CAPABILITY_WEAR_TELEMETRY)

    private var epoch = 0L
    private var activeNode: WearNodeInfo? = null
    private var handshakeInfo: WatchHandshakeInfo? = null

    override suspend fun refreshConnection() {
        val myEpoch = ++epoch
        if (!sessionAuthenticated()) {
            clearContext()
            mutableState.value = WatchConnectionState.Disconnected
            return
        }
        if (!gateway.isWearableApiAvailable()) {
            mutableState.value = WatchConnectionState.DataLayerUnavailable
            return
        }
        val allNodes = try {
            gateway.fetchConnectedNodes()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: WearApiException) {
            handleDiscoveryFailure(error)
            return
        } catch (_: RuntimeException) {
            clearContext()
            mutableState.value = WatchConnectionState.Error(WatchConnectionErrorCode.InvalidResponse)
            return
        }
        if (epoch != myEpoch) return
        if (allNodes.isEmpty()) {
            clearContext()
            mutableState.value = WatchConnectionState.NoWearNodes
            return
        }
        val capableNodes = try {
            gateway.fetchCapabilityNodes(WearProtocol.CAPABILITY_WEAR_TELEMETRY)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: WearApiException) {
            handleDiscoveryFailure(error)
            return
        } catch (_: RuntimeException) {
            clearContext()
            mutableState.value = WatchConnectionState.Error(WatchConnectionErrorCode.InvalidResponse)
            return
        }
        if (epoch != myEpoch) return
        val candidates = capableNodes.sortedBy { it.nodeId }
        val target = candidates.firstOrNull { it.isNearby } ?: candidates.firstOrNull()
        if (target == null) {
            clearContext()
            mutableState.value = WatchConnectionState.WearNodeDetectedWithoutMotoSos
            return
        }
        activeNode = target
        handshakeInfo = null
        val device = target.toDevice()
        if (!target.isNearby) {
            mutableState.value = WatchConnectionState.CompatibleNodeIndirect(device)
            return
        }
        mutableState.value = WatchConnectionState.CompatibleNodeNearby(device)
        performHandshake(myEpoch, target)
    }

    private suspend fun performHandshake(myEpoch: Long, node: WearNodeInfo) {
        val device = node.toDevice()
        mutableState.value = WatchConnectionState.Handshaking(device)
        val requestId = requestIdGenerator()
        val payload = transport.encodeHandshakeRequest(
            HandshakeRequest(requestId = requestId, timestampEpochMs = clock())
        )
        val result = try {
            withTimeout(timeoutMillis) {
                gateway.sendMessageRequest(node.nodeId, WearProtocol.PATH_HANDSHAKE, payload)
            }
        } catch (_: TimeoutCancellationException) {
            if (epoch != myEpoch) return
            mutableState.value = WatchConnectionState.Timeout(device)
            return
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: WearApiException) {
            if (epoch != myEpoch) return
            if (error.isWearApiUnavailable) {
                clearContext()
                mutableState.value = WatchConnectionState.DataLayerUnavailable
            } else {
                mutableState.value = WatchConnectionState.Error(WatchConnectionErrorCode.SendFailed, device)
            }
            return
        }
        if (epoch != myEpoch) return
        if (result is WearMessageResult.Failure && result.reason == WEAR_MESSAGE_DATA_LAYER_UNAVAILABLE) {
            clearContext()
            mutableState.value = WatchConnectionState.DataLayerUnavailable
            return
        }
        if (result !is WearMessageResult.Success || result.payload == null) {
            mutableState.value = WatchConnectionState.Error(WatchConnectionErrorCode.SendFailed, device)
            return
        }
        when (val decoded = transport.decodeHandshakeResponse(result.payload)) {
            is WearDecodeResult.Failure ->
                mutableState.value = WatchConnectionState.ProtocolIncompatible(device)
            is WearDecodeResult.Success -> {
                val response = decoded.value
                if (response.requestId != requestId || response.result != WearRpcResult.OK) {
                    mutableState.value = WatchConnectionState.ProtocolIncompatible(device)
                    return
                }
                if (epoch != myEpoch) return
                val handshake = WearProtocolMapper.toHandshakeInfo(response)
                handshakeInfo = handshake
                mutableState.value = WatchConnectionState.Connected(device, handshake)
            }
        }
    }

    override suspend fun requestStatus(): WatchRequestResult<WatchStatus> {
        val ready = pendingRequestAllowed() ?: return WatchRequestResult.NotConnected
        val (node, handshake) = ready
        val myEpoch = epoch
        val device = node.toDevice()
        mutableState.value = WatchConnectionState.RequestingStatus(device)
        val requestId = requestIdGenerator()
        val payload = transport.encodeStatusRequest(
            WatchStatusRequest(requestId = requestId, timestampEpochMs = clock())
        )
        val outcome = performRpc(
            myEpoch = myEpoch,
            node = node,
            path = WearProtocol.PATH_STATUS,
            requestId = requestId,
            payload = payload,
            decode = { transport.decodeStatusResponse(it) },
            requestIdOf = { it.requestId },
            resultOf = { it.result }
        )
        return finishRequest(myEpoch, node, handshake, outcome) { response ->
            WearProtocolMapper.toStatus(response)
        }
    }

    override suspend fun requestSnapshot(): WatchRequestResult<WatchSensorSnapshot> {
        val ready = pendingRequestAllowed() ?: return WatchRequestResult.NotConnected
        val (node, handshake) = ready
        val myEpoch = epoch
        val device = node.toDevice()
        mutableState.value = WatchConnectionState.RequestingSnapshot(device)
        val requestId = requestIdGenerator()
        val payload = transport.encodeSnapshotRequest(
            WatchSnapshotRequest(requestId = requestId, timestampEpochMs = clock())
        )
        val outcome = performRpc(
            myEpoch = myEpoch,
            node = node,
            path = WearProtocol.PATH_SNAPSHOT,
            requestId = requestId,
            payload = payload,
            decode = { transport.decodeSnapshotResponse(it) },
            requestIdOf = { it.requestId },
            resultOf = { it.result }
        )
        return finishRequest(myEpoch, node, handshake, outcome) { response ->
            WearProtocolMapper.toSnapshot(response)
        }
    }

    override fun reset() {
        epoch++
        clearContext()
        mutableState.value = WatchConnectionState.Disconnected
    }

    // -------------------------------------------------------------- helpers

    /** Devuelve el par (nodo, handshake) si una solicitud de datos es legal; null si no. */
    private fun pendingRequestAllowed(): Pair<WearNodeInfo, WatchHandshakeInfo>? {
        val current = mutableState.value
        if (current is WatchConnectionState.RequestingStatus ||
            current is WatchConnectionState.RequestingSnapshot
        ) {
            return null
        }
        if (!sessionAuthenticated()) return null
        val node = activeNode ?: return null
        val handshake = handshakeInfo ?: return null
        return node to handshake
    }

    private suspend fun <T, R> finishRequest(
        myEpoch: Long,
        node: WearNodeInfo,
        handshake: WatchHandshakeInfo,
        outcome: RpcOutcome<T>,
        toValue: (T) -> R
    ): WatchRequestResult<R> {
        val device = node.toDevice()
        return when (outcome) {
            is RpcOutcome.Success -> {
                if (epoch != myEpoch) return WatchRequestResult.NotConnected
                mutableState.value = WatchConnectionState.Connected(device, handshake)
                WatchRequestResult.Success(toValue(outcome.value))
            }
            is RpcOutcome.Timeout -> {
                if (epoch != myEpoch) return WatchRequestResult.NotConnected
                mutableState.value = WatchConnectionState.Timeout(device)
                WatchRequestResult.Timeout
            }
            is RpcOutcome.DataLayerUnavailable -> {
                if (epoch != myEpoch) return WatchRequestResult.NotConnected
                clearContext()
                mutableState.value = WatchConnectionState.DataLayerUnavailable
                WatchRequestResult.NotConnected
            }
            is RpcOutcome.SendFailed,
            is RpcOutcome.InvalidResponse -> {
                if (epoch != myEpoch) return WatchRequestResult.NotConnected
                mutableState.value = WatchConnectionState.Connected(device, handshake)
                WatchRequestResult.InvalidResponse
            }
            is RpcOutcome.Declined -> {
                if (epoch != myEpoch) return WatchRequestResult.NotConnected
                mutableState.value = WatchConnectionState.Connected(device, handshake)
                WatchRequestResult.Declined(outcome.reason)
            }
        }
    }

    private suspend fun <T> performRpc(
        myEpoch: Long,
        node: WearNodeInfo,
        path: String,
        requestId: String,
        payload: ByteArray,
        decode: (ByteArray) -> WearDecodeResult<T>,
        requestIdOf: (T) -> String,
        resultOf: (T) -> WearRpcResult
    ): RpcOutcome<T> {
        val result = try {
            withTimeout(timeoutMillis) {
                gateway.sendMessageRequest(node.nodeId, path, payload)
            }
        } catch (_: TimeoutCancellationException) {
            return if (epoch == myEpoch) RpcOutcome.Timeout else RpcOutcome.SendFailed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: WearApiException) {
            return if (error.isWearApiUnavailable) RpcOutcome.DataLayerUnavailable else RpcOutcome.SendFailed
        }
        if (epoch != myEpoch) return RpcOutcome.SendFailed
        if (result is WearMessageResult.Failure && result.reason == WEAR_MESSAGE_DATA_LAYER_UNAVAILABLE) {
            return RpcOutcome.DataLayerUnavailable
        }
        if (result !is WearMessageResult.Success) return RpcOutcome.SendFailed
        val bytes = result.payload
        if (bytes == null) return RpcOutcome.InvalidResponse
        val value = when (val decoded = decode(bytes)) {
            is WearDecodeResult.Failure -> return RpcOutcome.InvalidResponse
            is WearDecodeResult.Success -> decoded.value
        }
        if (requestIdOf(value) != requestId) return RpcOutcome.InvalidResponse
        val rpcResult = resultOf(value)
        if (rpcResult != WearRpcResult.OK) {
            return RpcOutcome.Declined(WearProtocolMapper.toDeclineReason(rpcResult))
        }
        return RpcOutcome.Success(value)
    }

    private fun sessionAuthenticated(): Boolean =
        authRepository.observeSession().value is SessionState.Authenticated

    private fun clearContext() {
        activeNode = null
        handshakeInfo = null
    }

    private fun handleDiscoveryFailure(error: WearApiException) {
        clearContext()
        mutableState.value = if (error.isWearApiUnavailable) {
            WatchConnectionState.DataLayerUnavailable
        } else {
            WatchConnectionState.Error(WatchConnectionErrorCode.InvalidResponse)
        }
    }

    private fun WearNodeInfo.toDevice(): WatchDeviceSummary =
        WatchDeviceSummary(displayName = displayName, isNearby = isNearby)

    private sealed interface RpcOutcome<out T> {
        data class Success<T>(val value: T) : RpcOutcome<T>
        data object Timeout : RpcOutcome<Nothing>
        data object DataLayerUnavailable : RpcOutcome<Nothing>
        data object SendFailed : RpcOutcome<Nothing>
        data object InvalidResponse : RpcOutcome<Nothing>
        data class Declined(val reason: WatchDeclineReason) : RpcOutcome<Nothing>
    }
}
