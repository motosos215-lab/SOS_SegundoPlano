package com.example.sos_segundoplano.data.local.push

import android.content.Context
import com.example.sos_segundoplano.domain.push.PendingMonitorAlert
import com.example.sos_segundoplano.domain.push.PendingMonitorAlertStore

class SharedPreferencesPendingMonitorAlertStore(
    context: Context,
    preferencesName: String = PREFERENCES_NAME
) : PendingMonitorAlertStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        preferencesName,
        Context.MODE_PRIVATE
    )
    private val lock = Any()

    override fun read(): PendingMonitorAlert? = synchronized(lock) {
        val attemptId = preferences.getString(KEY_ATTEMPT_ID, null)
            ?.takeIf { it.isNotBlank() }
            ?: return@synchronized null
        PendingMonitorAlert(
            notificationDeliveryAttemptId = attemptId,
            alertDispatchId = preferences.getString(KEY_ALERT_DISPATCH_ID, null),
            incidentId = preferences.getString(KEY_INCIDENT_ID, null),
            receivedAtEpochMillis = preferences.getLong(KEY_RECEIVED_AT, 0L)
        )
    }

    override fun save(alert: PendingMonitorAlert): Boolean = synchronized(lock) {
        preferences.edit()
            .putString(KEY_ATTEMPT_ID, alert.notificationDeliveryAttemptId)
            .putNullableString(KEY_ALERT_DISPATCH_ID, alert.alertDispatchId)
            .putNullableString(KEY_INCIDENT_ID, alert.incidentId)
            .putLong(KEY_RECEIVED_AT, alert.receivedAtEpochMillis)
            .commit()
    }

    override fun clear(): Boolean = synchronized(lock) {
        preferences.edit().clear().commit()
    }

    private fun android.content.SharedPreferences.Editor.putNullableString(
        key: String,
        value: String?
    ): android.content.SharedPreferences.Editor = if (value == null) remove(key) else putString(key, value)

    companion object {
        const val PREFERENCES_NAME = "motosos_pending_monitor_alert"
        const val KEY_ATTEMPT_ID = "notification_delivery_attempt_id"
        const val KEY_ALERT_DISPATCH_ID = "alert_dispatch_id"
        const val KEY_INCIDENT_ID = "incident_id"
        const val KEY_RECEIVED_AT = "received_at_epoch_millis"
    }
}
