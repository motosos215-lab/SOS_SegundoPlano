package com.example.sos_segundoplano.data.wear

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
            is WearDecodeResult.Success -> WearActionProtocolCodec.encodePhoneActionResponse(
                path,
                coordinator.manualSos(decoded.value, clock()),
            )
            is WearDecodeResult.Failure -> null
        }
        else -> null
    }

}
