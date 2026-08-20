package com.example.sos_segundoplano.data.remote.trip

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PendingTripFinishState {
    Pending,
    RetryPending,
    FailedPermanent
}

data class PendingTripFinish(
    val ownerUserId: String,
    val remoteTripId: String,
    val tripSessionKey: String,
    val clientFinishedAtUtc: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val attemptCount: Int = 0,
    val nextAttemptAtEpochMillis: Long? = null,
    val state: PendingTripFinishState = PendingTripFinishState.Pending,
    val lastErrorCode: String? = null
)

interface PendingTripFinishStore {
    val state: StateFlow<PendingTripFinish?>
    fun readForOwner(ownerUserId: String): PendingTripFinish?
    fun savePending(value: PendingTripFinish): Boolean
    fun markRetry(ownerUserId: String, remoteTripId: String, nextAttemptAtEpochMillis: Long?, errorCode: String?): Boolean
    fun markFailed(ownerUserId: String, remoteTripId: String, errorCode: String?): Boolean
    fun clearIfMatches(ownerUserId: String, remoteTripId: String): Boolean
    fun expedite(ownerUserId: String, nowEpochMillis: Long): Boolean
}

class SharedPreferencesPendingTripFinishStore(
    context: Context,
    preferencesName: String = PREFERENCES_NAME,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis
) : PendingTripFinishStore {
    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val lock = Any()
    private val mutableState = MutableStateFlow(readInternal())
    override val state: StateFlow<PendingTripFinish?> = mutableState.asStateFlow()

    override fun readForOwner(ownerUserId: String): PendingTripFinish? = synchronized(lock) {
        val owner = ownerUserId.normalized() ?: return@synchronized null
        readInternal()?.takeIf { it.ownerUserId == owner }
    }

    override fun savePending(value: PendingTripFinish): Boolean = synchronized(lock) {
        val normalized = value.normalized() ?: return@synchronized false
        val existing = readInternal()
        if (existing != null && (existing.ownerUserId != normalized.ownerUserId || existing.remoteTripId != normalized.remoteTripId)) {
            // Never overwrite an unsynchronized finish belonging to another trip/account.
            return@synchronized false
        }
        persist(normalized)
    }

    override fun markRetry(
        ownerUserId: String,
        remoteTripId: String,
        nextAttemptAtEpochMillis: Long?,
        errorCode: String?
    ): Boolean = updateIfMatches(ownerUserId, remoteTripId) { current ->
        current.copy(
            state = PendingTripFinishState.RetryPending,
            attemptCount = current.attemptCount + 1,
            nextAttemptAtEpochMillis = nextAttemptAtEpochMillis,
            lastErrorCode = errorCode.sanitized(),
            updatedAtEpochMillis = nowEpochMillis().coerceAtLeast(0L)
        )
    }

    override fun markFailed(ownerUserId: String, remoteTripId: String, errorCode: String?): Boolean =
        updateIfMatches(ownerUserId, remoteTripId) { current ->
            current.copy(
                state = PendingTripFinishState.FailedPermanent,
                attemptCount = current.attemptCount + 1,
                nextAttemptAtEpochMillis = null,
                lastErrorCode = errorCode.sanitized(),
                updatedAtEpochMillis = nowEpochMillis().coerceAtLeast(0L)
            )
        }

    override fun clearIfMatches(ownerUserId: String, remoteTripId: String): Boolean = synchronized(lock) {
        val owner = ownerUserId.normalized() ?: return@synchronized false
        val trip = remoteTripId.normalized() ?: return@synchronized false
        val current = readInternal() ?: return@synchronized true
        if (current.ownerUserId != owner || current.remoteTripId != trip) return@synchronized false
        val cleared = preferences.edit().clear().commit()
        if (cleared) mutableState.value = null
        cleared
    }

    override fun expedite(ownerUserId: String, nowEpochMillis: Long): Boolean = updateIfMatches(ownerUserId, null) { current ->
        if (current.state != PendingTripFinishState.RetryPending) {
            current
        } else {
            val normalizedNow = nowEpochMillis.coerceAtLeast(0L)
            current.copy(
                nextAttemptAtEpochMillis = normalizedNow,
                updatedAtEpochMillis = normalizedNow
            )
        }
    }

    private fun updateIfMatches(
        ownerUserId: String,
        remoteTripId: String?,
        transform: (PendingTripFinish) -> PendingTripFinish
    ): Boolean = synchronized(lock) {
        val owner = ownerUserId.normalized() ?: return@synchronized false
        val trip = remoteTripId?.normalized()
        val current = readInternal() ?: return@synchronized false
        if (current.ownerUserId != owner || (trip != null && current.remoteTripId != trip)) return@synchronized false
        persist(transform(current))
    }

    private fun persist(value: PendingTripFinish): Boolean {
        val editor = preferences.edit()
            .putString(KEY_OWNER_USER_ID, value.ownerUserId)
            .putString(KEY_REMOTE_TRIP_ID, value.remoteTripId)
            .putString(KEY_TRIP_SESSION_KEY, value.tripSessionKey)
            .putString(KEY_CLIENT_FINISHED_AT_UTC, value.clientFinishedAtUtc)
            .putLong(KEY_CREATED_AT, value.createdAtEpochMillis)
            .putLong(KEY_UPDATED_AT, value.updatedAtEpochMillis)
            .putInt(KEY_ATTEMPT_COUNT, value.attemptCount)
            .putString(KEY_STATE, value.state.name)
        if (value.nextAttemptAtEpochMillis == null) editor.remove(KEY_NEXT_ATTEMPT_AT)
        else editor.putLong(KEY_NEXT_ATTEMPT_AT, value.nextAttemptAtEpochMillis)
        if (value.lastErrorCode == null) editor.remove(KEY_LAST_ERROR_CODE)
        else editor.putString(KEY_LAST_ERROR_CODE, value.lastErrorCode)
        val saved = editor.commit()
        if (saved) mutableState.value = value
        return saved
    }

    private fun readInternal(): PendingTripFinish? {
        val owner = preferences.getString(KEY_OWNER_USER_ID, null).normalized() ?: return null
        val remoteTripId = preferences.getString(KEY_REMOTE_TRIP_ID, null).normalized() ?: return null
        val tripSessionKey = preferences.getString(KEY_TRIP_SESSION_KEY, null).normalized() ?: return null
        val finishedAt = preferences.getString(KEY_CLIENT_FINISHED_AT_UTC, null).normalized() ?: return null
        val createdAt = preferences.getLong(KEY_CREATED_AT, -1L)
        val updatedAt = preferences.getLong(KEY_UPDATED_AT, -1L)
        val attempts = preferences.getInt(KEY_ATTEMPT_COUNT, 0)
        val state = preferences.getString(KEY_STATE, null)?.let { runCatching { PendingTripFinishState.valueOf(it) }.getOrNull() }
            ?: return null
        if (createdAt < 0L || updatedAt < 0L || attempts < 0) return null
        return PendingTripFinish(
            ownerUserId = owner,
            remoteTripId = remoteTripId,
            tripSessionKey = tripSessionKey,
            clientFinishedAtUtc = finishedAt,
            createdAtEpochMillis = createdAt,
            updatedAtEpochMillis = updatedAt,
            attemptCount = attempts,
            nextAttemptAtEpochMillis = preferences.takeIf { it.contains(KEY_NEXT_ATTEMPT_AT) }?.getLong(KEY_NEXT_ATTEMPT_AT, -1L)?.takeIf { it >= 0L },
            state = state,
            lastErrorCode = preferences.getString(KEY_LAST_ERROR_CODE, null).sanitized()
        )
    }

    private fun PendingTripFinish.normalized(): PendingTripFinish? {
        val owner = ownerUserId.normalized() ?: return null
        val trip = remoteTripId.normalized() ?: return null
        val key = tripSessionKey.normalized() ?: return null
        val finishedAt = clientFinishedAtUtc.normalized() ?: return null
        if (createdAtEpochMillis < 0L || updatedAtEpochMillis < 0L || attemptCount < 0) return null
        return copy(
            ownerUserId = owner,
            remoteTripId = trip,
            tripSessionKey = key,
            clientFinishedAtUtc = finishedAt,
            lastErrorCode = lastErrorCode.sanitized()
        )
    }

    private fun String?.normalized(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
    private fun String?.sanitized(): String? = this?.replace(Regex("[\\r\\n\\t]"), " ")?.trim()?.takeIf { it.isNotEmpty() }?.take(120)

    companion object {
        const val PREFERENCES_NAME = "pending_trip_finish_v1"
        private const val KEY_OWNER_USER_ID = "owner_user_id"
        private const val KEY_REMOTE_TRIP_ID = "remote_trip_id"
        private const val KEY_TRIP_SESSION_KEY = "trip_session_key"
        private const val KEY_CLIENT_FINISHED_AT_UTC = "client_finished_at_utc"
        private const val KEY_CREATED_AT = "created_at_epoch_millis"
        private const val KEY_UPDATED_AT = "updated_at_epoch_millis"
        private const val KEY_ATTEMPT_COUNT = "attempt_count"
        private const val KEY_NEXT_ATTEMPT_AT = "next_attempt_at_epoch_millis"
        private const val KEY_STATE = "state"
        private const val KEY_LAST_ERROR_CODE = "last_error_code"
    }
}
