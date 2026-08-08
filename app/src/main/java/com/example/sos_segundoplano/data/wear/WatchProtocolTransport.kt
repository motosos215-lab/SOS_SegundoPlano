package com.example.sos_segundoplano.data.wear

import com.example.sos_segundoplano.wearprotocol.HandshakeRequest
import com.example.sos_segundoplano.wearprotocol.HandshakeResponse
import com.example.sos_segundoplano.wearprotocol.WatchSnapshotRequest
import com.example.sos_segundoplano.wearprotocol.WatchSnapshotResponse
import com.example.sos_segundoplano.wearprotocol.WatchStatusRequest
import com.example.sos_segundoplano.wearprotocol.WatchStatusResponse
import com.example.sos_segundoplano.wearprotocol.WearDecodeResult
import com.example.sos_segundoplano.wearprotocol.WearProtocolCodec

/**
 * Frontera de serialización del contrato compartido `:wear-protocol`.
 *
 * Aísla las llamadas que dependen del runtime de Android (`DataMap.toByteArray()` /
 * `DataMap.fromByteArray()`) para poder probar el repositorio en unit tests JVM con un fake.
 * La implementación de producción [WearProtocolCodecTransport] usa el código real del codec.
 */
interface WatchProtocolTransport {
    fun encodeHandshakeRequest(request: HandshakeRequest): ByteArray
    fun encodeStatusRequest(request: WatchStatusRequest): ByteArray
    fun encodeSnapshotRequest(request: WatchSnapshotRequest): ByteArray

    fun decodeHandshakeResponse(bytes: ByteArray): WearDecodeResult<HandshakeResponse>
    fun decodeStatusResponse(bytes: ByteArray): WearDecodeResult<WatchStatusResponse>
    fun decodeSnapshotResponse(bytes: ByteArray): WearDecodeResult<WatchSnapshotResponse>
}

/** Transport de producción que delega en [WearProtocolCodec]. */
object WearProtocolCodecTransport : WatchProtocolTransport {
    override fun encodeHandshakeRequest(request: HandshakeRequest): ByteArray =
        WearProtocolCodec.encodeHandshakeRequest(request)

    override fun encodeStatusRequest(request: WatchStatusRequest): ByteArray =
        WearProtocolCodec.encodeWatchStatusRequest(request)

    override fun encodeSnapshotRequest(request: WatchSnapshotRequest): ByteArray =
        WearProtocolCodec.encodeWatchSnapshotRequest(request)

    override fun decodeHandshakeResponse(bytes: ByteArray): WearDecodeResult<HandshakeResponse> =
        WearProtocolCodec.decodeHandshakeResponse(bytes)

    override fun decodeStatusResponse(bytes: ByteArray): WearDecodeResult<WatchStatusResponse> =
        WearProtocolCodec.decodeWatchStatusResponse(bytes)

    override fun decodeSnapshotResponse(bytes: ByteArray): WearDecodeResult<WatchSnapshotResponse> =
        WearProtocolCodec.decodeWatchSnapshotResponse(bytes)
}