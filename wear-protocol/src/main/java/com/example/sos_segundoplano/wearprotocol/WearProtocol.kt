package com.example.sos_segundoplano.wearprotocol

import java.util.UUID

/**
 * Contrato RPC V1 compartido entre la aplicación móvil MotoSOS y la aplicación Wear MotoSOS.
 *
 * Las rutas y capabilities aquí definidas son el único contrato soportado. El módulo Wear
 * futuro debe publicar [CAPABILITY_WEAR_TELEMETRY] y responder a las tres rutas mediante
 * `WearableListenerService.onRequest(...)`.
 */
object WearProtocol {
    const val PROTOCOL_VERSION: Int = 1
    const val SCHEMA_VERSION: Int = 1

    const val CAPABILITY_MOBILE_COMPANION: String = "motosos_mobile_companion_v1"
    const val CAPABILITY_WEAR_TELEMETRY: String = "motosos_wear_telemetry_v1"

    const val PATH_HANDSHAKE: String = "/motosos/v1/handshake"
    const val PATH_STATUS: String = "/motosos/v1/status"
    const val PATH_SNAPSHOT: String = "/motosos/v1/snapshot"

    /**
     * Límite de transporte del Wearable Data Layer (mensajes RPC, no datos de archivos).
     * Los payloads mayores se rechazan como inválidos.
     */
    const val MAX_PAYLOAD_BYTES: Int = 100 * 1024
}

/**
 * Genera el `requestId` del lado móvil. No incluye datos de la cuenta, del nodo ni tokens;
 * solo sirve para correlacionar la respuesta con la solicitud emitida.
 */
object WearRequestIdGenerator {
    fun newRequestId(): String = UUID.randomUUID().toString()
}