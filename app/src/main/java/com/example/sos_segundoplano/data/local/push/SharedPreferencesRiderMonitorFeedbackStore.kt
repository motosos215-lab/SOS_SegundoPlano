package com.example.sos_segundoplano.data.local.push

import android.content.Context
import com.example.sos_segundoplano.domain.push.RiderMonitorFeedbackMessage
import com.example.sos_segundoplano.domain.push.RiderMonitorFeedbackStore
import com.example.sos_segundoplano.domain.push.RiderMonitorFeedbackType
import org.json.JSONArray
import org.json.JSONObject

class SharedPreferencesRiderMonitorFeedbackStore(
    context: Context,
    preferencesName: String = PREFERENCES_NAME
) : RiderMonitorFeedbackStore {
    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val lock = Any()

    override fun readAll(): List<RiderMonitorFeedbackMessage> = synchronized(lock) {
        val raw = preferences.getString(KEY_MESSAGES, null) ?: return@synchronized emptyList()
        runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val owner = item.optString("ownerUserId").takeIf { it.isNotBlank() } ?: continue
                    val attemptId = item.optString("notificationDeliveryAttemptId").takeIf { it.isNotBlank() } ?: continue
                    val type = RiderMonitorFeedbackType.fromWireName(item.optString("eventType")) ?: continue
                    add(
                        RiderMonitorFeedbackMessage(
                            ownerUserId = owner,
                            type = type,
                            notificationDeliveryAttemptId = attemptId,
                            incidentId = item.nullableString("incidentId"),
                            alertDispatchId = item.nullableString("alertDispatchId"),
                            monitorAlertAttemptId = item.nullableString("monitorAlertAttemptId"),
                            occurredAtUtc = item.nullableString("occurredAtUtc"),
                            receivedAtEpochMillis = item.optLong("receivedAtEpochMillis", 0L),
                            body = item.nullableString("body"),
                            isRead = item.optBoolean("isRead", false)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    override fun saveAll(messages: List<RiderMonitorFeedbackMessage>): Boolean = synchronized(lock) {
        val array = JSONArray()
        messages.forEach { message ->
            array.put(
                JSONObject().apply {
                    put("ownerUserId", message.ownerUserId)
                    put("eventType", message.type.wireName)
                    put("notificationDeliveryAttemptId", message.notificationDeliveryAttemptId)
                    putNullable("incidentId", message.incidentId)
                    putNullable("alertDispatchId", message.alertDispatchId)
                    putNullable("monitorAlertAttemptId", message.monitorAlertAttemptId)
                    putNullable("occurredAtUtc", message.occurredAtUtc)
                    put("receivedAtEpochMillis", message.receivedAtEpochMillis)
                    putNullable("body", message.body)
                    put("isRead", message.isRead)
                }
            )
        }
        preferences.edit().putString(KEY_MESSAGES, array.toString()).commit()
    }

    private fun JSONObject.nullableString(key: String): String? =
        optString(key).takeIf { it.isNotBlank() && it != "null" }

    private fun JSONObject.putNullable(key: String, value: String?) {
        if (value == null) put(key, JSONObject.NULL) else put(key, value)
    }

    companion object {
        const val PREFERENCES_NAME = "motosos_rider_monitor_feedback"
        const val KEY_MESSAGES = "messages_json"
    }
}
