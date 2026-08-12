package com.example.sos_segundoplano.data.remote.incident

import android.content.Context

enum class RemoteIncidentSyncState { Pending, Created }

data class RemoteIncidentLink(
    val localIncidentId: Long,
    val clientIncidentId: String,
    val remoteTripId: String?,
    val remoteIncidentId: String?,
    val syncState: RemoteIncidentSyncState,
    val updatedAtEpochMillis: Long,
    val clientAlertRequestId: String? = null,
    val detectedAtUtc: String? = null,
    val remoteAlertDispatchId: String? = null
)

interface RemoteIncidentLinkStore {
    fun read(clientIncidentId: String): RemoteIncidentLink?
    fun readPendingManualSos(): RemoteIncidentLink? = null
    fun save(link: RemoteIncidentLink): Boolean
}

class InMemoryRemoteIncidentLinkStore : RemoteIncidentLinkStore {
    private val links = mutableMapOf<String, RemoteIncidentLink>()

    override fun read(clientIncidentId: String): RemoteIncidentLink? = synchronized(links) {
        links[clientIncidentId]
    }

    override fun readPendingManualSos(): RemoteIncidentLink? = synchronized(links) {
        links.values.lastOrNull {
            it.syncState == RemoteIncidentSyncState.Pending &&
                !it.clientAlertRequestId.isNullOrBlank() &&
                !it.detectedAtUtc.isNullOrBlank()
        }
    }

    override fun save(link: RemoteIncidentLink): Boolean = synchronized(links) {
        links[link.clientIncidentId] = link
        true
    }
}

class SharedPreferencesRemoteIncidentLinkStore(
    context: Context,
    preferencesName: String = PREFERENCES_NAME
) : RemoteIncidentLinkStore {
    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val lock = Any()

    override fun read(clientIncidentId: String): RemoteIncidentLink? = synchronized(lock) {
        val normalizedClientId = clientIncidentId.normalized() ?: return@synchronized null
        val keyPrefix = keyPrefix(normalizedClientId)
        if (!preferences.contains("$keyPrefix.$KEY_LOCAL_INCIDENT_ID")) return@synchronized null
        val localIncidentId = preferences.getLong("$keyPrefix.$KEY_LOCAL_INCIDENT_ID", -1L)
        val remoteTripId = preferences.getString("$keyPrefix.$KEY_REMOTE_TRIP_ID", null).normalized()
        val state = preferences.getString("$keyPrefix.$KEY_SYNC_STATE", null)
            ?.let { runCatching { RemoteIncidentSyncState.valueOf(it) }.getOrNull() }
            ?: return@synchronized null
        val updatedAt = preferences.getLong("$keyPrefix.$KEY_UPDATED_AT", -1L)
        if (localIncidentId < 0L || updatedAt < 0L) return@synchronized null
        RemoteIncidentLink(
            localIncidentId = localIncidentId,
            clientIncidentId = normalizedClientId,
            remoteTripId = remoteTripId,
            remoteIncidentId = preferences.getString("$keyPrefix.$KEY_REMOTE_INCIDENT_ID", null).normalized(),
            syncState = state,
            updatedAtEpochMillis = updatedAt,
            clientAlertRequestId = preferences.getString("$keyPrefix.$KEY_CLIENT_ALERT_REQUEST_ID", null).normalized(),
            detectedAtUtc = preferences.getString("$keyPrefix.$KEY_DETECTED_AT_UTC", null).normalized(),
            remoteAlertDispatchId = preferences.getString("$keyPrefix.$KEY_REMOTE_ALERT_DISPATCH_ID", null).normalized()
        )
    }

    override fun readPendingManualSos(): RemoteIncidentLink? = synchronized(lock) {
        val clientIncidentId = preferences.getString(KEY_PENDING_MANUAL_SOS_CLIENT_INCIDENT_ID, null).normalized()
            ?: return@synchronized null
        read(clientIncidentId)?.takeIf {
            it.syncState == RemoteIncidentSyncState.Pending &&
                !it.clientAlertRequestId.isNullOrBlank() &&
                !it.detectedAtUtc.isNullOrBlank()
        }
    }

    override fun save(link: RemoteIncidentLink): Boolean = synchronized(lock) {
        val clientIncidentId = link.clientIncidentId.normalized() ?: return@synchronized false
        val remoteTripId = link.remoteTripId.normalized()
        val remoteIncidentId = link.remoteIncidentId?.normalized()
        val clientAlertRequestId = link.clientAlertRequestId.normalized()
        val detectedAtUtc = link.detectedAtUtc.normalized()
        val remoteAlertDispatchId = link.remoteAlertDispatchId.normalized()
        if (link.localIncidentId < 0L || link.updatedAtEpochMillis < 0L) return@synchronized false
        if (link.syncState == RemoteIncidentSyncState.Created && (remoteTripId == null || remoteIncidentId == null)) {
            return@synchronized false
        }
        if (clientAlertRequestId != null && detectedAtUtc == null) return@synchronized false
        if (link.syncState == RemoteIncidentSyncState.Created && clientAlertRequestId != null && remoteAlertDispatchId == null) {
            return@synchronized false
        }
        val keyPrefix = keyPrefix(clientIncidentId)
        val editor = preferences.edit()
            .putLong("$keyPrefix.$KEY_LOCAL_INCIDENT_ID", link.localIncidentId)
            .putNullableString("$keyPrefix.$KEY_REMOTE_TRIP_ID", remoteTripId)
            .putNullableString("$keyPrefix.$KEY_REMOTE_INCIDENT_ID", remoteIncidentId)
            .putNullableString("$keyPrefix.$KEY_CLIENT_ALERT_REQUEST_ID", clientAlertRequestId)
            .putNullableString("$keyPrefix.$KEY_DETECTED_AT_UTC", detectedAtUtc)
            .putNullableString("$keyPrefix.$KEY_REMOTE_ALERT_DISPATCH_ID", remoteAlertDispatchId)
            .putString("$keyPrefix.$KEY_SYNC_STATE", link.syncState.name)
            .putLong("$keyPrefix.$KEY_UPDATED_AT", link.updatedAtEpochMillis)
        if (clientAlertRequestId != null && link.syncState == RemoteIncidentSyncState.Pending) {
            editor.putString(KEY_PENDING_MANUAL_SOS_CLIENT_INCIDENT_ID, clientIncidentId)
        } else if (preferences.getString(KEY_PENDING_MANUAL_SOS_CLIENT_INCIDENT_ID, null) == clientIncidentId) {
            editor.remove(KEY_PENDING_MANUAL_SOS_CLIENT_INCIDENT_ID)
        }
        editor.commit()
    }

    private fun android.content.SharedPreferences.Editor.putNullableString(
        key: String,
        value: String?
    ): android.content.SharedPreferences.Editor = if (value == null) remove(key) else putString(key, value)

    private fun keyPrefix(clientIncidentId: String): String = "incident.$clientIncidentId"

    private fun String?.normalized(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        const val PREFERENCES_NAME = "remote_incident_links_v1"
        const val KEY_LOCAL_INCIDENT_ID = "local_incident_id"
        const val KEY_REMOTE_TRIP_ID = "remote_trip_id"
        const val KEY_REMOTE_INCIDENT_ID = "remote_incident_id"
        const val KEY_CLIENT_ALERT_REQUEST_ID = "client_alert_request_id"
        const val KEY_DETECTED_AT_UTC = "detected_at_utc"
        const val KEY_REMOTE_ALERT_DISPATCH_ID = "remote_alert_dispatch_id"
        const val KEY_PENDING_MANUAL_SOS_CLIENT_INCIDENT_ID = "pending_manual_sos_client_incident_id"
        const val KEY_SYNC_STATE = "sync_state"
        const val KEY_UPDATED_AT = "updated_at_epoch_millis"
    }
}
