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
        PushDiagnostics.debug(PushDiagnostics.onNewToken(token.isNotBlank()))
        PushTokenHandler(PushTokenProvider.get(applicationContext)).onNewToken(token)
        PushTokenRegistrationProvider.scheduleSync()
        PushDiagnostics.debug(PushDiagnostics.onNewTokenSyncScheduled())
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
        PushDiagnostics.debug(PushDiagnostics.messageReceived(message.data.size, message.notification != null))
        PushDiagnostics.debug(
            PushDiagnostics.monitorAlertHandled(
                result.validPayload,
                result.stored,
                result.notificationResult
            )
        )
    }

}

object PushDiagnostics {
    const val TAG = "MotoSOS.Push"

    fun debug(message: String) {
        if (!BuildConfig.DEBUG) return
        runCatching { Log.d(TAG, message) }
    }

    fun initialFetchStarted(): String = "event=fcm_initial_fetch_started"

    fun initialFetchResult(success: Boolean, error: Throwable? = null): String = if (success) {
        "event=fcm_initial_fetch_result result=success token_present=true"
    } else {
        "event=fcm_initial_fetch_result result=failure error=${error?.javaClass?.simpleName ?: "Unknown"}"
    }

    fun onNewToken(tokenPresent: Boolean): String =
        "event=fcm_on_new_token token_present=$tokenPresent"

    fun onNewTokenSyncScheduled(): String = "event=fcm_on_new_token_scheduled_sync"

    fun syncTrigger(trigger: String): String = "event=push_sync_trigger trigger=$trigger"

    fun syncStarted(): String = "event=push_sync_started"

    fun syncState(pendingTokenPresent: Boolean, remoteRegistrationPresent: Boolean): String =
        "event=push_sync_state pending_token_present=$pendingTokenPresent " +
            "remote_registration_present=$remoteRegistrationPresent"

    fun syncResult(result: String, code: String? = null): String = buildString {
        append("event=push_sync_result result=")
        append(result)
        sanitizedCode(code)?.let { append(" code=").append(it) }
    }

    fun registerStarted(): String = "event=push_register_started"

    fun registerResult(result: String, status: Int? = null, code: String? = null): String = buildString {
        append("event=push_register_result result=")
        append(result)
        status?.let { append(" status=").append(it) }
        sanitizedCode(code)?.let { append(" code=").append(it) }
    }

    fun registrationPersisted(): String = "event=push_registration_persisted remote_registration_present=true"

    fun registrationPersistFailed(): String = "event=push_registration_persist_failed"

    fun logoutRevokeState(remoteRegistrationPresent: Boolean): String =
        "event=push_logout_revoke_state remote_registration_present=$remoteRegistrationPresent"

    fun logoutRevokeResult(result: String): String = "event=push_logout_revoke_result result=$result"

    fun messageReceived(dataKeyCount: Int, hasNotification: Boolean): String =
        "fcm_message_received dataKeyCount=$dataKeyCount hasNotification=$hasNotification"

    fun monitorAlertHandled(
        validPayload: Boolean,
        stored: Boolean,
        notificationResult: MonitorAlertNotificationResult?
    ): String =
        "monitor_alert_handled validPayload=$validPayload stored=$stored " +
            "notificationResult=${notificationResult?.name ?: "not_attempted"}"

    private fun sanitizedCode(code: String?): String? = code
        ?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,160}")) }
}
