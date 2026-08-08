package com.example.sos_segundoplano.wearprotocol

/**
 * Errores que el codec puede reportar al rechazar un payload. No son mensajes de negocio:
 * solo clasifican qué regla de validación falló, para que la capa de UI los tradazca a texto.
 */
sealed interface WearProtocolError {
    data object PayloadTooLarge : WearProtocolError
    data object CorruptPayload : WearProtocolError
    data class UnexpectedRoute(val expected: String, val actual: String?) : WearProtocolError
    data class ProtocolVersionMismatch(val expected: Int, val actual: Int) : WearProtocolError
    data class SchemaVersionMismatch(val expected: Int, val actual: Int) : WearProtocolError
    data object EmptyRequestId : WearProtocolError
    data class RequiredFieldMissing(val field: String) : WearProtocolError
    data class InvalidEnumValue(val field: String, val value: String?) : WearProtocolError
    data class InvalidBatteryPercentage(val percentage: Int) : WearProtocolError
    data class NonFiniteValue(val field: String) : WearProtocolError
    data class InvalidHeartRateBpm(val bpm: Float) : WearProtocolError
    data class InvalidCapability(val expected: String, val actual: String) : WearProtocolError
    data class InvalidTimestamp(val field: String, val value: Long) : WearProtocolError
    data class InvalidSequence(val sequence: Long) : WearProtocolError
}
