package com.example.sos_segundoplano.domain.wear

/**
 * Máquina de estados de la conexión con el reloj Wear. Refleja exactamente el estado que
 * muestra la pantalla de conexión.
 *
 * Reglas de negocio:
 *  - Solicitar datos (`status`/`snapshot`) solo es válido cuando el reloj está
 *    [Connected] o [Timeout] (nunca con [CompatibleNodeIndirect]).
 *  - [WearNodeDetectedWithoutMotoSOS] significa que hay reloj vinculado pero sin la
 *    capability `motosos_wear_telemetry_v1`.
 *  - [Error] es un fallo de la infraestructura (envío o respuesta no válida), no un
 *    rechazo del protocolo.
 */
sealed interface WatchConnectionState {
    data object Loading : WatchConnectionState
    data object DataLayerUnavailable : WatchConnectionState
    data object NoWearNodes : WatchConnectionState
    data object WearNodeDetectedWithoutMotoSos : WatchConnectionState
    data class CompatibleNodeIndirect(val device: WatchDeviceSummary) : WatchConnectionState
    data class CompatibleNodeNearby(val device: WatchDeviceSummary) : WatchConnectionState
    data class Handshaking(val device: WatchDeviceSummary) : WatchConnectionState
    data class Connected(
        val device: WatchDeviceSummary,
        val handshake: WatchHandshakeInfo
    ) : WatchConnectionState
    data class ProtocolIncompatible(val device: WatchDeviceSummary) : WatchConnectionState
    data class RequestingStatus(val device: WatchDeviceSummary) : WatchConnectionState
    data class RequestingSnapshot(val device: WatchDeviceSummary) : WatchConnectionState
    data class Timeout(val device: WatchDeviceSummary) : WatchConnectionState
    data object Disconnected : WatchConnectionState
    data class Error(
        val code: WatchConnectionErrorCode,
        val device: WatchDeviceSummary? = null
    ) : WatchConnectionState
}

/** Códigos concretos del estado [WatchConnectionState.Error]. */
enum class WatchConnectionErrorCode {
    SendFailed,
    InvalidResponse
}