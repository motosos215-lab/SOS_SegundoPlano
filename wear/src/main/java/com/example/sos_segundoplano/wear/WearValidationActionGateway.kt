package com.example.sos_segundoplano.wear

import android.content.Context
import com.example.sos_segundoplano.wearprotocol.WearProtocol
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface WearValidationActionResult {
    data object Sent : WearValidationActionResult
    data object NoActiveCountdown : WearValidationActionResult
    data object CompanionUnavailable : WearValidationActionResult
    data object Timeout : WearValidationActionResult
    data object TransportFailure : WearValidationActionResult
}

internal fun interface CompanionMessageTransport {
    suspend fun sendMessage(nodeId: String, path: String, payload: ByteArray)
}

internal class WearValidationActionGateway(
    context: Context,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val nodeSource: CompanionNodeSource? = null,
    private val messageTransport: CompanionMessageTransport? = null,
) : WearValidationActionClient {
    private val appContext = context.applicationContext
    private val capabilityClient by lazy { Wearable.getCapabilityClient(appContext) }
    private val messageClient by lazy { Wearable.getMessageClient(appContext) }

    override suspend fun confirmSafe(): WearValidationActionResult =
        send("confirm_safe", WearDataLayerProtocol.PATH_VALIDATION_CONFIRM_SAFE)

    override suspend fun requestHelp(): WearValidationActionResult =
        send("request_help", WearDataLayerProtocol.PATH_VALIDATION_REQUEST_HELP)

    private suspend fun send(action: String, path: String): WearValidationActionResult {
        val status = WearValidationStateStore.state.value
        if (status?.isCountdownActive != true || status.sessionId == null || status.assessmentId == null) {
            return WearValidationActionResult.NoActiveCountdown
        }
        val node = try {
            (nodeSource?.capabilityNodes()
                ?: capabilityClient.getCapability(
                    WearProtocol.CAPABILITY_MOBILE_COMPANION,
                    CapabilityClient.FILTER_REACHABLE
                ).await().nodes.map { CompanionNode(it.id, it.isNearby) })
                .sortedWith(compareByDescending<CompanionNode> { it.isNearby }.thenBy { it.nodeId })
                .firstOrNull()
        } catch (_: Exception) {
            return WearValidationActionResult.TransportFailure
        } ?: return WearValidationActionResult.CompanionUnavailable
        val payload = WearDataLayerProtocol.encodeValidationResponse(
            action,
            status.sessionId,
            status.assessmentId,
            "wear-${status.sessionId}-${status.assessmentId}-$action"
        )
        return try {
            withTimeout(timeoutMillis) {
                messageTransport?.sendMessage(node.nodeId, path, payload)
                    ?: messageClient.sendMessage(node.nodeId, path, payload).await()
            }
            WearValidationActionResult.Sent
        } catch (_: TimeoutCancellationException) {
            WearValidationActionResult.Timeout
        } catch (_: Exception) {
            WearValidationActionResult.TransportFailure
        }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
        addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
        addOnCanceledListener { if (continuation.isActive) continuation.cancel() }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 5_000L
    }
}
