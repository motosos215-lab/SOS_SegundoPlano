package com.example.sos_segundoplano.data.remote.trip

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PersistedRemoteTripSession(
    val remoteTripId: String,
    val updatedAtEpochMillis: Long
)

interface RemoteTripSessionPersistence {
    fun read(): PersistedRemoteTripSession?
    fun save(session: PersistedRemoteTripSession): Boolean
    fun clear(): Boolean
}

fun interface RemoteTripSessionClock {
    fun nowEpochMillis(): Long
}

class PersistentRemoteTripSessionStore(
    private val persistence: RemoteTripSessionPersistence,
    private val clock: RemoteTripSessionClock = RemoteTripSessionClock(System::currentTimeMillis)
) : RemoteTripSessionStore {
    private val mutableRemoteTripId = MutableStateFlow(restoreRemoteTripId())
    override val remoteTripId: StateFlow<String?> = mutableRemoteTripId.asStateFlow()

    override fun setRemoteTripId(remoteTripId: String): Boolean {
        val normalized = remoteTripId.trim().takeIf { it.isNotEmpty() } ?: return false
        val now = clock.nowEpochMillis().takeIf { it >= 0L } ?: return false
        if (!persistence.save(PersistedRemoteTripSession(normalized, now))) return false
        mutableRemoteTripId.value = normalized
        return true
    }

    override fun clearRemoteTripId(): Boolean {
        if (!persistence.clear()) return false
        mutableRemoteTripId.value = null
        return true
    }

    private fun restoreRemoteTripId(): String? {
        val persisted = persistence.read() ?: return null
        val normalized = persisted.remoteTripId.trim().takeIf { it.isNotEmpty() }
        if (normalized == null || persisted.updatedAtEpochMillis < 0L) {
            persistence.clear()
            return null
        }
        return normalized
    }
}

class SharedPreferencesRemoteTripSessionPersistence(
    context: Context,
    preferencesName: String = PREFERENCES_NAME
) : RemoteTripSessionPersistence {
    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    override fun read(): PersistedRemoteTripSession? {
        val remoteTripId = preferences.getString(KEY_REMOTE_TRIP_ID, null) ?: return null
        if (!preferences.contains(KEY_UPDATED_AT)) return null
        return PersistedRemoteTripSession(
            remoteTripId = remoteTripId,
            updatedAtEpochMillis = preferences.getLong(KEY_UPDATED_AT, -1L)
        )
    }

    override fun save(session: PersistedRemoteTripSession): Boolean = preferences.edit()
        .putString(KEY_REMOTE_TRIP_ID, session.remoteTripId)
        .putLong(KEY_UPDATED_AT, session.updatedAtEpochMillis)
        .commit()

    override fun clear(): Boolean = preferences.edit().clear().commit()

    companion object {
        const val PREFERENCES_NAME = "remote_trip_session_v1"
        const val KEY_REMOTE_TRIP_ID = "remote_trip_id"
        const val KEY_UPDATED_AT = "updated_at_epoch_millis"
    }
}
