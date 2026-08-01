package com.example.sos_segundoplano.data.validation

import android.content.Context
import com.example.sos_segundoplano.data.signals.WearDataLayerProtocol
import com.example.sos_segundoplano.domain.validation.FalsePositiveValidationState
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.atomic.AtomicLong

class WearValidationStatusNotifier(context: Context) : FalsePositiveValidationNotifier {
    private val appContext = context.applicationContext
    private val messageClient = Wearable.getMessageClient(appContext)
    private val nodeClient = Wearable.getNodeClient(appContext)
    private val messages = AtomicLong(0L)

    override fun onValidationStateChanged(state: FalsePositiveValidationState) {
        val payload = WearDataLayerProtocol.encodeValidationState(state, "phone-validation-${messages.incrementAndGet()}").toByteArray()
        nodeClient.connectedNodes.addOnSuccessListener { nodes ->
            nodes.forEach { node ->
                messageClient.sendMessage(node.id, WearDataLayerProtocol.PATH_VALIDATION_STATUS, payload)
            }
        }
    }
}
