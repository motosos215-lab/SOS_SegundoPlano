package com.example.sos_segundoplano.wear

internal object WearValidationNotificationActions {
    const val CONFIRM_SAFE = "com.example.sos_segundoplano.wear.action.CONFIRM_SAFE"
    const val REQUEST_HELP = "com.example.sos_segundoplano.wear.action.REQUEST_HELP"
}

internal interface WearValidationActionClient {
    suspend fun confirmSafe(): WearValidationActionResult
    suspend fun requestHelp(): WearValidationActionResult
}

internal class WearValidationNotificationActionHandler(
    private val client: WearValidationActionClient
) {
    suspend fun handle(action: String?) {
        when (action) {
            WearValidationNotificationActions.CONFIRM_SAFE -> client.confirmSafe()
            WearValidationNotificationActions.REQUEST_HELP -> client.requestHelp()
        }
    }
}
