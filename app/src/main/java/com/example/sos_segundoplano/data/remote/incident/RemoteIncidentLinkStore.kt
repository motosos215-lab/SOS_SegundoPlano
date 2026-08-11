package com.example.sos_segundoplano.data.remote.incident

import android.content.Context

enum class RemoteIncidentSyncState { Pending, Created }

data class RemoteIncidentLink(
    val localIncidentId: Long,
    val clientIncidentId: String,
    val remoteTripId: String?,
    val remoteIncidentId: String?,
    val syncState: RemoteIncidentSyncState,
    val updatedAtEpochMillis: Long
)

interface RemoteIncidentLinkStore {
    fun read(clientIncidentId: String): RemoteIncidentLink?
    fun save(link: RemoteIncidentLink): Boolean
}

class InMemoryRemoteIncidentLinkStore : RemoteIncidentLinkStore {
    private val links = mutableMapOf<String, RemoteIncidentLink>()

    override fun read(clientIncidentId: String): RemoteIncidentLink? = synchronized(links) {
        links[clientIncidentId]
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
            updatedAtEpochMillis = updatedAt
        )
    }

    override fun save(link: RemoteIncidentLink): Boolean = synchronized(lock) {
        val clientIncidentId = link.clientIncidentId.normalized() ?: return@synchronized false
        val remoteTripId = link.remoteTripId.normalized()
        val remoteIncidentId = link.remoteIncidentId?.normalized()
        if (link.localIncidentId < 0L || link.updatedAtEpochMillis < 0L) return@synchronized false
        if (link.syncState == RemoteIncidentSyncState.Created && (remoteTripId == null || remoteIncidentId == null)) {
            return@synchronized false
        }
        val keyPrefix = keyPrefix(clientIncidentId)
        preferences.edit()
            .putLong("$keyPrefix.$KEY_LOCAL_INCIDENT_ID", link.localIncidentId)
            .putNullableString("$keyPrefix.$KEY_REMOTE_TRIP_ID", remoteTripId)
            .putNullableString("$keyPrefix.$KEY_REMOTE_INCIDENT_ID", remoteIncidentId)
            .putString("$keyPrefix.$KEY_SYNC_STATE", link.syncState.name)
            .putLong("$keyPrefix.$KEY_UPDATED_AT", link.updatedAtEpochMillis)
            .commit()
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
        const val KEY_SYNC_STATE = "sync_state"
        const val KEY_UPDATED_AT = "updated_at_epoch_millis"
    }
}
