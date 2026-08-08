package com.example.sos_segundoplano.data.wear

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Implementación real del gateway sobre Google Play Services. Se mantiene el patrón del
 * repositorio existente: clientes `lazy` obtenidos con `Wearable.get*Client(appContext)` y
 * comprobación de disponibilidad con [GoogleApiAvailability].
 */
class GooglePlayWearGateway(
    context: Context
) : WearPlatformGateway {
    private val appContext = context.applicationContext
    private val nodeClient by lazy { Wearable.getNodeClient(appContext) }
    private val capabilityClient by lazy { Wearable.getCapabilityClient(appContext) }
    private val messageClient by lazy { Wearable.getMessageClient(appContext) }

    override fun isWearableApiAvailable(): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appContext) ==
            ConnectionResult.SUCCESS

    override suspend fun fetchConnectedNodes(): List<WearNodeInfo> =
        nodeClient.connectedNodes.awaitGoogleTask().map { it.toWearNodeInfo() }

    override suspend fun fetchCapabilityNodes(capability: String): List<WearNodeInfo> =
        capabilityClient.getCapability(capability, CapabilityClient.FILTER_REACHABLE)
            .awaitGoogleTask()
            .nodes
            .map { it.toWearNodeInfo() }

    override suspend fun sendMessageRequest(
        nodeId: String,
        path: String,
        payload: ByteArray
    ): WearMessageResult {
        return try {
            WearMessageResult.Success(messageClient.sendRequest(nodeId, path, payload).awaitGoogleTask())
        } catch (error: WearApiException) {
            if (error.isWearApiUnavailable) return WearMessageResult.Failure(WEAR_MESSAGE_DATA_LAYER_UNAVAILABLE)
            WearMessageResult.Failure("message_send_failed")
        }
    }

    override fun observeCapabilityChanges(capability: String): Flow<Unit> = callbackFlow {
        val listener = CapabilityClient.OnCapabilityChangedListener { trySend(Unit) }
        val registration = capabilityClient.addListener(listener, capability)
        registration.addOnFailureListener { error -> close(WearApiException.from(error)) }
        awaitClose { capabilityClient.removeListener(listener, capability) }
    }

    private fun Node.toWearNodeInfo(): WearNodeInfo =
        WearNodeInfo(nodeId = id, displayName = displayName, isNearby = isNearby)
}

internal const val WEAR_MESSAGE_DATA_LAYER_UNAVAILABLE = "wear_data_layer_unavailable"

internal class WearApiException private constructor(
    val statusCode: Int?,
    message: String?,
    cause: Throwable?
) : RuntimeException(message ?: "google_wear_task_failed", cause) {
    val isWearApiUnavailable: Boolean
        get() = statusCode == ConnectionResult.API_UNAVAILABLE ||
            statusCode == CommonStatusCodes.API_NOT_CONNECTED

    companion object {
        fun from(error: Exception): WearApiException {
            val api = error as? ApiException
            return WearApiException(
                statusCode = api?.statusCode,
                message = api?.statusMessage ?: error.message,
                cause = error
            )
        }

        fun apiUnavailable(): WearApiException = WearApiException(
            statusCode = ConnectionResult.API_UNAVAILABLE,
            message = "Wearable.API is not available",
            cause = null
        )
    }
}

/**
 * Puente mínimo de Google `Task` a coroutines. No bloquea el hilo de main. La cancelación
 * del `Task` subyacente se propaga cancelando la coroutine; si la coroutine se cancela
 * primero, los callbacks posteriores se ignoran mediante `continuation.isActive`.
 */
private suspend fun <T> Task<T>.awaitGoogleTask(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { value ->
        if (continuation.isActive) continuation.resume(value)
    }
    addOnFailureListener { error ->
        if (continuation.isActive) continuation.resumeWithException(WearApiException.from(error))
    }
    addOnCanceledListener {
        if (continuation.isActive) continuation.cancel()
    }
}
