package com.example.sos_segundoplano.data.signals

import android.content.Context
import com.example.sos_segundoplano.domain.signals.WearableSample
import com.example.sos_segundoplano.domain.signals.WearableStatus
import com.example.sos_segundoplano.domain.signals.WearableStatusPolicy
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable

class MobileWearableSignalSource(
    context: Context,
    private val store: TripSignalStore
) : SignalSource, DataClient.OnDataChangedListener {
    private val appContext = context.applicationContext
    private val dataClient by lazy { Wearable.getDataClient(appContext) }
    private val messageClient by lazy { Wearable.getMessageClient(appContext) }
    private val nodeClient by lazy { Wearable.getNodeClient(appContext) }
    private var started = false
    private var latestNode: Node? = null

    override fun start() {
        if (started) return
        if (!isWearableApiAvailable()) {
            store.updateWearable(WearableSample(status = WearableStatus.NotInstalledOrUnavailable))
            return
        }
        dataClient.addListener(this)
        started = true
        refreshNodes(sendStart = true)
    }

    override fun stop() {
        if (!started) return
        sendCommand(WearDataLayerProtocol.PATH_TRIP_STOP)
        dataClient.removeListener(this)
        started = false
        latestNode = null
        store.updateWearable(WearableSample(status = WearableStatus.Disconnected))
    }

    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { event ->
            if (event.type != DataEvent.TYPE_CHANGED) return@forEach
            val path = event.dataItem.uri.path
            if (path != WearDataLayerProtocol.PATH_SIGNALS_LATEST && path != WearDataLayerProtocol.PATH_WATCH_STATUS) return@forEach
            val node = latestNode
            val sample = WearDataLayerProtocol.decodeWearable(
                DataMapItem.fromDataItem(event.dataItem).dataMap,
                nodeId = node?.id,
                isNearby = node?.isNearby == true
            )
            store.updateWearable(sample.copy(status = WearableStatusPolicy.applyFreshness(sample.status, sample.lastUpdatedMillis, System.currentTimeMillis())))
        }
    }

    private fun refreshNodes(sendStart: Boolean) {
        nodeClient.connectedNodes
            .addOnSuccessListener { nodes ->
                latestNode = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
                val node = latestNode
                store.updateWearable(
                    WearableSample(
                        nodeId = node?.id,
                        isNearby = node?.isNearby == true,
                        status = WearableStatusPolicy.fromNodes(
                            hasWearableApi = true,
                            hasNode = node != null,
                            isNearby = node?.isNearby == true
                        ),
                        lastUpdatedMillis = System.currentTimeMillis()
                    )
                )
                if (sendStart) sendCommand(WearDataLayerProtocol.PATH_TRIP_START)
            }
            .addOnFailureListener {
                store.updateWearable(WearableSample(status = WearableStatus.Error("nodes_unavailable")))
            }
    }

    private fun sendCommand(path: String) {
        val node = latestNode ?: return
        messageClient.sendMessage(node.id, path, ByteArray(0))
    }

    private fun isWearableApiAvailable(): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appContext) == ConnectionResult.SUCCESS
}
