package com.example.sos_segundoplano.wear

import android.content.Context
import android.util.Log
import com.example.sos_segundoplano.wearprotocol.FinishTripActionRequest
import com.example.sos_segundoplano.wearprotocol.ManualSosActionRequest
import com.example.sos_segundoplano.wearprotocol.PhoneActionResponse
import com.example.sos_segundoplano.wearprotocol.StartTripActionRequest
import com.example.sos_segundoplano.wearprotocol.TripStateRequest
import com.example.sos_segundoplano.wearprotocol.TripStateResponse
import com.example.sos_segundoplano.wearprotocol.WearActionProtocolCodec
import com.example.sos_segundoplano.wearprotocol.WearDecodeResult
import com.example.sos_segundoplano.wearprotocol.WearProtocol
import com.google.android.gms.tasks.Task
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface MobileCompanionResult<out T> {
    data class Success<T>(val value: T) : MobileCompanionResult<T>
    data object CompanionUnavailable : MobileCompanionResult<Nothing>
    data object Timeout : MobileCompanionResult<Nothing>
    data object TransportFailure : MobileCompanionResult<Nothing>
    data object DecodeFailure : MobileCompanionResult<Nothing>
}

internal data class CompanionNode(val nodeId: String, val isNearby: Boolean)
internal fun interface CompanionNodeSource { suspend fun capabilityNodes(): List<CompanionNode> }
internal fun interface CompanionRequestTransport { suspend fun sendRequest(nodeId: String, path: String, payload: ByteArray): ByteArray }

internal interface MobileCompanionClient {
    suspend fun getTripState(): MobileCompanionResult<TripStateResponse>
    suspend fun startTrip(commandId: String): MobileCompanionResult<PhoneActionResponse>
    suspend fun finishTrip(commandId: String, remoteTripId: String): MobileCompanionResult<PhoneActionResponse>
    suspend fun manualSos(commandId: String, remoteTripId: String): MobileCompanionResult<PhoneActionResponse>
}

internal class MobileCompanionGateway(
    context: Context,
    private val requestId: () -> String = { UUID.randomUUID().toString() },
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val nodeSource: CompanionNodeSource? = null,
    private val requestTransport: CompanionRequestTransport? = null,
) : MobileCompanionClient {
    private val appContext = context.applicationContext
    private val capabilityClient by lazy { Wearable.getCapabilityClient(appContext) }
    private val messageClient by lazy { Wearable.getMessageClient(appContext) }

    override suspend fun getTripState(): MobileCompanionResult<TripStateResponse> {
        val id = requestId()
        val result = request(
            path = WearProtocol.PATH_TRIP_STATE,
            payload = WearActionProtocolCodec.encodeTripStateRequest(TripStateRequest(id, System.currentTimeMillis())),
            id = id,
            logTripState = true
        ) {
            WearActionProtocolCodec.decodeTripStateResponse(it)
        }
        logTripStateResult(result)
        return result
    }

    override suspend fun startTrip(commandId: String): MobileCompanionResult<PhoneActionResponse> = action(
        WearProtocol.PATH_ACTION_START_TRIP, commandId,
        { id -> WearActionProtocolCodec.encodeStartTripAction(StartTripActionRequest(id, commandId, System.currentTimeMillis())) },
    )

    override suspend fun finishTrip(commandId: String, remoteTripId: String): MobileCompanionResult<PhoneActionResponse> = action(
        WearProtocol.PATH_ACTION_FINISH_TRIP, commandId,
        { id -> WearActionProtocolCodec.encodeFinishTripAction(FinishTripActionRequest(id, commandId, remoteTripId, System.currentTimeMillis())) },
    )

    override suspend fun manualSos(commandId: String, remoteTripId: String): MobileCompanionResult<PhoneActionResponse> = action(
        WearProtocol.PATH_ACTION_MANUAL_SOS, commandId,
        { id -> WearActionProtocolCodec.encodeManualSosAction(ManualSosActionRequest(id, commandId, remoteTripId, System.currentTimeMillis())) },
    )

    private suspend fun action(path: String, commandId: String, encode: (String) -> ByteArray): MobileCompanionResult<PhoneActionResponse> {
        require(commandId.isNotBlank())
        val id = requestId()
        return request(path, encode(id), id) { WearActionProtocolCodec.decodePhoneActionResponse(it, path) }
    }

    private suspend fun <T> request(
        path: String,
        payload: ByteArray,
        id: String,
        logTripState: Boolean = false,
        decode: (ByteArray) -> WearDecodeResult<T>
    ): MobileCompanionResult<T> {
        debug("event=capability_query_start capability=${WearProtocol.CAPABILITY_MOBILE_COMPANION} filter=reachable")
        val nodes = try {
            nodeSource?.capabilityNodes()
                ?: capabilityClient
                    .getCapability(WearProtocol.CAPABILITY_MOBILE_COMPANION, CapabilityClient.FILTER_REACHABLE)
                    .await()
                    .nodes
                    .map { CompanionNode(it.id, it.isNearby) }
        } catch (error: Exception) {
            logCapabilityFailure(error)
            return MobileCompanionResult.TransportFailure
        }
        debug("event=capability_query_success reachableCount=${nodes.size} nearbyCount=${nodes.count { it.isNearby }}")
        val node = nodes
            .sortedWith(compareByDescending<CompanionNode> { it.isNearby }.thenBy { it.nodeId })
            .firstOrNull()
            ?: run {
                debug("event=capability_query_empty")
                return MobileCompanionResult.CompanionUnavailable
            }
        debug("event=node_selected nearby=${node.isNearby}")
        if (logTripState) debug("event=trip_state_request_start path=$path")
        val bytes = try {
            withTimeout(timeoutMillis) {
                requestTransport?.sendRequest(node.nodeId, path, payload)
                    ?: messageClient.sendRequest(node.nodeId, path, payload).await()
            }
        } catch (_: TimeoutCancellationException) {
            if (logTripState) debug("event=trip_state_request_timeout")
            return MobileCompanionResult.Timeout
        } catch (error: Exception) {
            if (logTripState) logTripStateFailure(error)
            return MobileCompanionResult.TransportFailure
        }
        if (logTripState) debug("event=trip_state_request_success responseBytes=${bytes.size}")
        val value = when (val parsed = decode(bytes)) {
            is WearDecodeResult.Success -> parsed.value
            is WearDecodeResult.Failure -> return MobileCompanionResult.DecodeFailure
        }
        return if (when (value) {
                is TripStateResponse -> value.requestId
                is PhoneActionResponse -> value.requestId
                else -> ""
            } == id
        ) {
            MobileCompanionResult.Success(value)
        } else {
            MobileCompanionResult.DecodeFailure
        }
    }

    private fun logCapabilityFailure(error: Exception) {
        val status = (error as? ApiException)?.statusCode?.let { " statusCode=$it" }.orEmpty()
        warning("event=capability_query_failure exception=${error.javaClass.simpleName}$status")
    }

    private fun logTripStateFailure(error: Exception) {
        val status = (error as? ApiException)?.statusCode?.let { " statusCode=$it" }.orEmpty()
        warning("event=trip_state_request_failure exception=${error.javaClass.simpleName}$status")
    }

    private fun logTripStateResult(result: MobileCompanionResult<*>) {
        val type = when (result) {
            is MobileCompanionResult.Success -> "success"
            MobileCompanionResult.CompanionUnavailable -> "companion_unavailable"
            MobileCompanionResult.Timeout -> "timeout"
            MobileCompanionResult.TransportFailure -> "transport_failure"
            MobileCompanionResult.DecodeFailure -> "invalid_response"
        }
        debug("event=trip_state_result result=$type")
    }

    private fun debug(message: String) {
        if (BuildConfig.DEBUG) Log.d(LOG_TAG, message)
    }

    private fun warning(message: String) {
        if (BuildConfig.DEBUG) Log.w(LOG_TAG, message)
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
        addOnSuccessListener { if (c.isActive) c.resume(it) }; addOnFailureListener { if (c.isActive) c.resumeWithException(it) }; addOnCanceledListener { if (c.isActive) c.cancel() }
    }
    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 5_000L
        const val LOG_TAG = "MotoSOS.WearLink"
    }
}
