package com.example.sos_segundoplano.data.wear

import kotlinx.coroutines.flow.Flow

/**
 * Abstracción mínima sobre el Wearable Data Layer. Permite probar el repositorio con un fake
 * en tests JVM sin tocar las APIs de Google Play Services.
 *
 * Solo se usa el transporte de mensajes RPC (`MessageClient.sendRequest`), Capacities
 * (`CapabilityClient` FILTER_REACHABLE) y el listado de nodos (`NodeClient.connectedNodes`).
 */
interface WearPlatformGateway {
    fun isWearableApiAvailable(): Boolean

    /** Nodos conectados en este momento (tengan o no la capability MotoSOS). */
    suspend fun fetchConnectedNodes(): List<WearNodeInfo>

    /** Nodos alcanzables que publican la capability indicada. */
    suspend fun fetchCapabilityNodes(capability: String): List<WearNodeInfo>

    /**
     * Envía un mensaje RPC y espera la respuesta en bytes. Devuelve el payload de la
     * respuesta (posiblemente vacío) en [WearMessageResult.Success].
     */
    suspend fun sendMessageRequest(nodeId: String, path: String, payload: ByteArray): WearMessageResult

    /** Cambios de capability alcanzable publicados por Google Play Services Wearable. */
    fun observeCapabilityChanges(capability: String): Flow<Unit>
}

/** Información mínima de un nodo del Wearable Data Layer. */
data class WearNodeInfo(
    val nodeId: String,
    val displayName: String,
    val isNearby: Boolean
)

/** Resultado del envío de un mensaje RPC. */
sealed interface WearMessageResult {
    data class Success(val payload: ByteArray?) : WearMessageResult
    data class Failure(val reason: String) : WearMessageResult
}
