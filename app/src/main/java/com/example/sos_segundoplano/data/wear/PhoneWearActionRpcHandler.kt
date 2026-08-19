package com.example.sos_segundoplano.data.wear

import android.util.Log
import com.example.sos_segundoplano.BuildConfig
import com.example.sos_segundoplano.wearprotocol.WearActionProtocolCodec
import com.example.sos_segundoplano.wearprotocol.WearDecodeResult
import com.example.sos_segundoplano.wearprotocol.WearProtocol

class PhoneWearActionRpcHandler(
    private val coordinator: WearPhoneActionCoordinator,
    private val clock: () -> Long = System::currentTimeMillis
) {
    suspend fun handle(path: String, payload: ByteArray): ByteArray? = when (path) {
        WearProtocol.PATH_TRIP_STATE -> when (val decoded = WearActionProtocolCodec.decodeTripStateRequest(payload)) {
            is WearDecodeResult.Success -> WearActionProtocolCodec.encodeTripStateResponse(coordinator.currentTripState(decoded.value))
            is WearDecodeResult.Failure -> null
        }
        WearProtocol.PATH_ACTION_START_TRIP -> when (val decoded = WearActionProtocolCodec.decodeStartTripAction(payload)) {
            is WearDecodeResult.Success -> WearActionProtocolCodec.encodePhoneActionResponse(
                path,
                coordinator.startTrip(decoded.value, clock()),
            )
            is WearDecodeResult.Failure -> null
        }
        WearProtocol.PATH_ACTION_FINISH_TRIP -> when (val decoded = WearActionProtocolCodec.decodeFinishTripAction(payload)) {
            is WearDecodeResult.Success -> WearActionProtocolCodec.encodePhoneActionResponse(
                path,
                coordinator.finishTrip(decoded.value, clock()),
            )
            is WearDecodeResult.Failure -> null
        }
        WearProtocol.PATH_ACTION_MANUAL_SOS -> when (val decoded = WearActionProtocolCodec.decodeManualSosAction(payload)) {
            is WearDecodeResult.Success -> {
                val response = coordinator.manualSos(decoded.value, clock())
                logManualSosResult(response)
                WearActionProtocolCodec.encodePhoneActionResponse(path, response)
            }
            is WearDecodeResult.Failure -> null
        }
        else -> null
    }

    private fun logManualSosResult(response: com.example.sos_segundoplano.wearprotocol.PhoneActionResponse) {
        if (BuildConfig.DEBUG) {
            val sanitizedCode = response.sanitizedCode ?: "none"
            Log.d(
                LOG_TAG,
                "event=phone_action_result action=manual_sos result=${response.result.wireValue} code=$sanitizedCode"
            )
        }
    }

    private companion object {
        const val LOG_TAG = "MotoSOS.WearLink"
    }
}
