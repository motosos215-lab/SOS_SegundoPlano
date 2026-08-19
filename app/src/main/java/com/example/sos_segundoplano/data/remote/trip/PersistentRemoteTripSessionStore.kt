package com.example.sos_segundoplano.data.remote.trip

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PersistedRemoteTripSession(
    val remoteTripId: String,
    val updatedAtEpochMillis: Long,
    val startedAtEpochMillis: Long? = null,
    val tripSessionKey: String? = null
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
    private val restoredSession = restoreSession()
    private val mutableRemoteTripId = MutableStateFlow(restoredSession?.remoteTripId)
    private val mutableStartedAtEpochMs = MutableStateFlow(restoredSession?.startedAtEpochMillis)
    private val mutableTripSessionKey = MutableStateFlow(restoredSession?.tripSessionKey)
    override val remoteTripId: StateFlow<String?> = mutableRemoteTripId.asStateFlow()
    override val startedAtEpochMs: StateFlow<Long?> = mutableStartedAtEpochMs.asStateFlow()
    override val tripSessionKey: StateFlow<String?> = mutableTripSessionKey.asStateFlow()

    override fun setActiveSession(remoteTripId: String, startedAtEpochMs: Long?, tripSessionKey: String?): Boolean {
        val normalized = remoteTripId.trim().takeIf { it.isNotEmpty() } ?: return false
        val now = clock.nowEpochMillis().takeIf { it >= 0L } ?: return false
        val nextStartedAt = if (mutableRemoteTripId.value == normalized && mutableStartedAtEpochMs.value != null) {
            mutableStartedAtEpochMs.value
        } else {
            startedAtEpochMs?.takeIf { it >= 0L }
        }
        val explicitKey = tripSessionKey?.trim()?.takeIf { runCatching { java.util.UUID.fromString(it) }.isSuccess }
        val key = explicitKey ?: mutableTripSessionKey.value.takeIf { mutableRemoteTripId.value == normalized }
        if (!persistence.save(PersistedRemoteTripSession(normalized, now, nextStartedAt, key))) return false
        mutableTripSessionKey.value = key
        mutableRemoteTripId.value = normalized
        mutableStartedAtEpochMs.value = nextStartedAt
        return true
    }

    override fun setRemoteTripId(remoteTripId: String): Boolean = setActiveSession(remoteTripId, null)

    override fun setStartedAtEpochMs(startedAtEpochMs: Long?): Boolean {
        val remoteTripId = mutableRemoteTripId.value ?: return false
        return setActiveSession(remoteTripId, startedAtEpochMs)
    }

    override fun clearRemoteTripId(): Boolean {
        if (!persistence.clear()) return false
        mutableRemoteTripId.value = null
        mutableStartedAtEpochMs.value = null
        mutableTripSessionKey.value = null
        return true
    }

    override fun clearIfMatches(tripSessionKey: String, remoteTripId: String): RemoteTripSessionClearResult {
        val currentTrip = mutableRemoteTripId.value ?: return RemoteTripSessionClearResult.AlreadyEmpty
        val currentKey = mutableTripSessionKey.value ?: return RemoteTripSessionClearResult.LegacyUncorrelated
        if (currentTrip != remoteTripId || currentKey != tripSessionKey) return RemoteTripSessionClearResult.DifferentTrip
        return if (clearRemoteTripId()) RemoteTripSessionClearResult.Cleared else RemoteTripSessionClearResult.DifferentTrip
    }

    private fun restoreSession(): PersistedRemoteTripSession? {
        val persisted = persistence.read() ?: return null
        val normalized = persisted.remoteTripId.trim().takeIf { it.isNotEmpty() }
        if (normalized == null || persisted.updatedAtEpochMillis < 0L) {
            persistence.clear()
            return null
        }
        return persisted.copy(remoteTripId = normalized, startedAtEpochMillis = persisted.startedAtEpochMillis?.takeIf { it >= 0L })
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
            updatedAtEpochMillis = preferences.getLong(KEY_UPDATED_AT, -1L),
            startedAtEpochMillis = preferences.takeIf { it.contains(KEY_STARTED_AT) }?.getLong(KEY_STARTED_AT, -1L)?.takeIf { it >= 0L }
            , tripSessionKey = preferences.getString(KEY_TRIP_SESSION_KEY, null)
        )
    }

    override fun save(session: PersistedRemoteTripSession): Boolean = preferences.edit()
        .putString(KEY_REMOTE_TRIP_ID, session.remoteTripId)
        .putLong(KEY_UPDATED_AT, session.updatedAtEpochMillis)
        .apply { if (session.startedAtEpochMillis == null) remove(KEY_STARTED_AT) else putLong(KEY_STARTED_AT, session.startedAtEpochMillis) }
        .apply { if (session.tripSessionKey == null) remove(KEY_TRIP_SESSION_KEY) else putString(KEY_TRIP_SESSION_KEY, session.tripSessionKey) }
        .commit()

    override fun clear(): Boolean = preferences.edit().clear().commit()

    companion object {
        const val PREFERENCES_NAME = "remote_trip_session_v1"
        const val KEY_REMOTE_TRIP_ID = "remote_trip_id"
        const val KEY_UPDATED_AT = "updated_at_epoch_millis"
        const val KEY_STARTED_AT = "started_at_epoch_millis"
        const val KEY_TRIP_SESSION_KEY = "trip_session_key"
    }
}
