package com.example.sos_segundoplano.push

import android.util.Log
import com.example.sos_segundoplano.BuildConfig
import com.example.sos_segundoplano.core.push.MonitorAlertProvider
import com.example.sos_segundoplano.core.push.PushTokenProvider
import com.example.sos_segundoplano.core.push.PushTokenRegistrationProvider
import com.example.sos_segundoplano.domain.push.PushTokenHandler
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MotoSosFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        PushTokenHandler(PushTokenProvider.get(applicationContext)).onNewToken(token)
        PushTokenRegistrationProvider.scheduleSync()
        if (BuildConfig.DEBUG) Log.d(TAG, PushDiagnostics.tokenUpdated())
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val notificationFactory = MonitorAlertNotificationFactory(applicationContext)
        val result = MonitorAlertMessageHandler(
            coordinator = MonitorAlertProvider.get(applicationContext),
            presenter = notificationFactory::show
        ).handle(
            data = message.data,
            title = message.notification?.title,
            body = message.notification?.body
        )
        if (BuildConfig.DEBUG) {
            Log.d(TAG, PushDiagnostics.messageReceived(message.data.size, message.notification != null))
            Log.d(
                TAG,
                PushDiagnostics.monitorAlertHandled(
                    result.validPayload,
                    result.stored,
                    result.notificationResult
                )
            )
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

    fun monitorAlertHandled(
        validPayload: Boolean,
        stored: Boolean,
        notificationResult: MonitorAlertNotificationResult?
    ): String =
        "monitor_alert_handled validPayload=$validPayload stored=$stored " +
            "notificationResult=${notificationResult?.name ?: "not_attempted"}"
}
