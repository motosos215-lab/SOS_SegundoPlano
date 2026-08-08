package com.example.sos_segundoplano.wearprotocol

/**
 * Resultado RPC declarado por el nodo Wear. Son valores estables del contrato y no deben
 * sustituirse por mensajes de texto.
 */
enum class WearRpcResult(val wireValue: String) {
    OK("ok"),
    NOT_READY("not_ready"),
    SENSOR_UNAVAILABLE("sensor_unavailable"),
    PERMISSION_DENIED("permission_denied"),
    PROTOCOL_MISMATCH("protocol_mismatch"),
    INVALID_REQUEST("invalid_request"),
    INTERNAL_ERROR("internal_error");

    companion object {
        fun fromWire(value: String?): WearRpcResult? = entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * Estado de permiso de la frecuencia cardiaca declarado por el reloj. No se asume permiso
 * concedido por el simple hecho de recibir un valor.
 */
enum class SensorPermissionState(val wireValue: String) {
    GRANTED("granted"),
    DENIED("denied"),
    NOT_REQUESTED("not_requested"),
    NOT_REQUIRED("not_required"),
    UNAVAILABLE("unavailable");

    companion object {
        fun fromWire(value: String?): SensorPermissionState? = entries.firstOrNull { it.wireValue == value }
    }
}