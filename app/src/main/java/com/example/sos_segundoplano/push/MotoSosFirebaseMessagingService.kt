package com.example.sos_segundoplano.push

import android.util.Log
import com.example.sos_segundoplano.BuildConfig
import com.example.sos_segundoplano.core.push.PushTokenProvider
import com.example.sos_segundoplano.domain.push.PushTokenHandler
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MotoSosFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        PushTokenHandler(PushTokenProvider.get(applicationContext)).onNewToken(token)
        if (BuildConfig.DEBUG) Log.d(TAG, PushDiagnostics.tokenUpdated())
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, PushDiagnostics.messageReceived(message.data.size, message.notification != null))
        }
    }

    private companion object {
        const val TAG = "MotoSosPush"
    }
}

object PushDiagnostics {
    fun tokenUpdated(): String = "fcm_token_updated"

    fun messageReceived(dataKeyCount: Int, hasNotification: Boolean): String =
        "fcm_message_received dataKeyCount=$dataKeyCount hasNotification=$hasNotification"
}
