package com.example.sos_segundoplano.data.validation

import com.example.sos_segundoplano.data.signals.WearDataLayerProtocol
import com.example.sos_segundoplano.domain.validation.UserValidationAction
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class PhoneValidationWearableListenerService : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        val action = when (messageEvent.path) {
            WearDataLayerProtocol.PATH_VALIDATION_CONFIRM_SAFE -> UserValidationAction.ConfirmSafe
            WearDataLayerProtocol.PATH_VALIDATION_REQUEST_HELP -> UserValidationAction.RequestHelp
            else -> return
        }
        val response = WearDataLayerProtocol.decodeValidationResponse(messageEvent.data, action) ?: return
        FalsePositiveValidationCoordinatorProvider.coordinator.submitResponse(response)
    }
}
