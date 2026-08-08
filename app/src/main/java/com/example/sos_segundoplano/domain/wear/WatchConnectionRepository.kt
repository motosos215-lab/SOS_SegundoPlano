package com.example.sos_segundoplano.domain.wear

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Repositorio de la conexión con el reloj Wear OS.
 *
 * Responsabilidades:
 *  - descubrir nodos con la capability `motosos_wear_telemetry_v1`;
 *  - seleccionar un nodo compatible de forma determinista (cercano primero);
 *  - ejecutar el handshake V1 contra el nodo seleccionado;
 *  - exponer solicitudes de estado y de lecturas de sensores;
 *  - descartar respuestas obsoletas al cambiar de sesión o de nodo;
 *  - nunca exponer el `nodeId` a la capa de UI.
 */
interface WatchConnectionRepository {
    val connectionState: StateFlow<WatchConnectionState>

    /** Eventos de Data Layer que requieren repetir discovery mientras la pantalla está activa. */
    val connectionChanges: Flow<Unit>

    /** Rebusca nodos y (si hay un nodo cercano compatible) vuelve a hacer handshake. */
    suspend fun refreshConnection()

    /** Solicita la ruta `/motosos/v1/status`. Solo válido con reloj conectado. */
    suspend fun requestStatus(): WatchRequestResult<WatchStatus>

    /** Solicita la ruta `/motosos/v1/snapshot`. Solo válido con reloj conectado. */
    suspend fun requestSnapshot(): WatchRequestResult<WatchSensorSnapshot>

    /** Cancela operaciones en curso y deja la conexión en [WatchConnectionState.Disconnected]. */
    fun reset()
}

/** Resultado de una solicitud RPC al reloj. */
sealed interface WatchRequestResult<out T> {
    data class Success<T>(val value: T) : WatchRequestResult<T>
    data object Timeout : WatchRequestResult<Nothing>
    data object NotConnected : WatchRequestResult<Nothing>
    data class Declined(val reason: WatchDeclineReason) : WatchRequestResult<Nothing>
    data object InvalidResponse : WatchRequestResult<Nothing>
}

/** Motivo por el que el reloj rechazó una solicitud en la respuesta RPC. */
enum class WatchDeclineReason {
    NotReady,
    SensorUnavailable,
    PermissionDenied,
    ProtocolMismatch,
    InvalidRequest,
    InternalError
}
