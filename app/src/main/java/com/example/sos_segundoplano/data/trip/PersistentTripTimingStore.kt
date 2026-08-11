package com.example.sos_segundoplano.data.trip

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.example.sos_segundoplano.domain.trip.ElapsedRealtimeClock
import com.example.sos_segundoplano.domain.trip.TripTimingState
import com.example.sos_segundoplano.domain.trip.TripTimingStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PersistedTripTiming(
    val startedAtElapsedRealtimeMillis: Long,
    val bootSessionId: Long
)

interface TripTimingPersistence {
    fun read(): PersistedTripTiming?
    fun save(value: PersistedTripTiming): Boolean
    fun clear()
}

fun interface BootSessionProvider {
    fun currentBootSessionId(): Long?
}

class DefaultTripTimingStore(
    private val persistence: TripTimingPersistence,
    private val clock: ElapsedRealtimeClock,
    private val bootSessionProvider: BootSessionProvider
) : TripTimingStore {
    private val mutableStates = MutableStateFlow(restoreState())
    override val states: StateFlow<TripTimingState> = mutableStates.asStateFlow()

    override fun beginConfirmedTrip() {
        val startedAt = clock.nowMillis()
        if (startedAt < 0L) {
            persistence.clear()
            mutableStates.value = TripTimingState.Unknown
            return
        }
        val bootSessionId = bootSessionProvider.currentBootSessionId()
        if (bootSessionId != null && bootSessionId >= 0L) {
            persistence.save(PersistedTripTiming(startedAt, bootSessionId))
        } else {
            persistence.clear()
        }
        mutableStates.value = TripTimingState.Active(startedAt)
    }

    override fun clear() {
        persistence.clear()
        mutableStates.value = TripTimingState.Unknown
    }

    private fun restoreState(): TripTimingState {
        val persisted = persistence.read() ?: return TripTimingState.Unknown
        val currentBoot = bootSessionProvider.currentBootSessionId()
        val now = clock.nowMillis()
        val valid = currentBoot != null && currentBoot >= 0L &&
            currentBoot == persisted.bootSessionId &&
            persisted.startedAtElapsedRealtimeMillis >= 0L &&
            now >= persisted.startedAtElapsedRealtimeMillis
        if (!valid) {
            persistence.clear()
            return TripTimingState.Unknown
        }
        return TripTimingState.Active(persisted.startedAtElapsedRealtimeMillis)
    }
}

class SharedPreferencesTripTimingPersistence(context: Context) : TripTimingPersistence {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun read(): PersistedTripTiming? {
        if (!preferences.contains(KEY_STARTED_AT) || !preferences.contains(KEY_BOOT_SESSION)) return null
        return PersistedTripTiming(
            startedAtElapsedRealtimeMillis = preferences.getLong(KEY_STARTED_AT, -1L),
            bootSessionId = preferences.getLong(KEY_BOOT_SESSION, -1L)
        )
    }

    override fun save(value: PersistedTripTiming): Boolean = preferences.edit()
        .putLong(KEY_STARTED_AT, value.startedAtElapsedRealtimeMillis)
        .putLong(KEY_BOOT_SESSION, value.bootSessionId)
        .commit()

    override fun clear() {
        preferences.edit().clear().commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "trip_timing_v1"
        const val KEY_STARTED_AT = "started_at_elapsed_realtime_millis"
        const val KEY_BOOT_SESSION = "boot_session_id"
    }
}

object AndroidElapsedRealtimeClock : ElapsedRealtimeClock {
    override fun nowMillis(): Long = SystemClock.elapsedRealtime()
}

class AndroidBootSessionProvider(context: Context) : BootSessionProvider {
    private val contentResolver = context.applicationContext.contentResolver

    override fun currentBootSessionId(): Long? = try {
        Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT).toLong()
    } catch (_: Settings.SettingNotFoundException) {
        null
    } catch (_: SecurityException) {
        null
    }
}

object TripTimingStoreProvider {
    @Volatile private var installed: TripTimingStore? = null

    val store: TripTimingStore
        get() = checkNotNull(installed) { "TripTimingStoreProvider is not initialized" }

    fun initialize(context: Context): TripTimingStore = installed ?: synchronized(this) {
        installed ?: DefaultTripTimingStore(
            persistence = SharedPreferencesTripTimingPersistence(context),
            clock = AndroidElapsedRealtimeClock,
            bootSessionProvider = AndroidBootSessionProvider(context)
        ).also { installed = it }
    }
}
