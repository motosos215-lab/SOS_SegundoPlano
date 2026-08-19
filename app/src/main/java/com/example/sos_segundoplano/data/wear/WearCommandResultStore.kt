package com.example.sos_segundoplano.data.wear

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

enum class WearCommandAction { StartTrip, FinishTrip, ManualSos }
enum class WearCommandState { TerminalSuccess, TerminalRejected, PartialSideEffect, Retryable }

data class WearCommandRecord(
    val commandId: String,
    val action: WearCommandAction,
    val state: WearCommandState,
    val sanitizedCode: String?,
    val remoteTripId: String?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)

interface WearCommandResultStore {
    fun find(commandId: String, action: WearCommandAction): WearCommandRecord?
    fun save(record: WearCommandRecord)
    fun prune(nowEpochMs: Long)
}

internal interface WearCommandRecordStorage {
    fun read(): List<WearCommandRecord>
    fun write(records: List<WearCommandRecord>)
}

/** Small, process-death-safe idempotency cache. It stores no request body or credentials. */
class SharedPreferencesWearCommandResultStore internal constructor(
    private val storage: WearCommandRecordStorage,
    private val clock: () -> Long = System::currentTimeMillis,
) : WearCommandResultStore {
    constructor(
        context: Context,
        clock: () -> Long = System::currentTimeMillis,
    ) : this(
        storage = SharedPreferencesWearCommandRecordStorage(
            context.applicationContext.getSharedPreferences("wear_command_results", Context.MODE_PRIVATE),
        ),
        clock = clock,
    )

    @Synchronized override fun find(commandId: String, action: WearCommandAction): WearCommandRecord? {
        prune(clock())
        return storage.read().firstOrNull { it.commandId == commandId && it.action == action }
    }

    @Synchronized override fun save(record: WearCommandRecord) {
        require(record.commandId.isNotBlank())
        val retained = storage.read().filterNot { it.commandId == record.commandId && it.action == record.action }
        storage.write((retained + record).sortedByDescending { it.updatedAtEpochMs }.take(MAX_ENTRIES))
    }

    @Synchronized override fun prune(nowEpochMs: Long) {
        storage.write(storage.read().filter { nowEpochMs - it.updatedAtEpochMs in 0..TTL_MILLIS }.take(MAX_ENTRIES))
    }

    private companion object {
        const val MAX_ENTRIES = 48
        const val TTL_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}

private class SharedPreferencesWearCommandRecordStorage(
    private val preferences: SharedPreferences,
) : WearCommandRecordStorage {
    override fun read(): List<WearCommandRecord> = runCatching {
        val array = JSONArray(preferences.getString(KEY, "[]"))
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(WearCommandRecord(item.getString("commandId"), WearCommandAction.valueOf(item.getString("action")), WearCommandState.valueOf(item.getString("state")), item.optString("code").takeIf { it.isNotBlank() }, item.optString("trip").takeIf { it.isNotBlank() }, item.getLong("created"), item.getLong("updated")))
            }
        }
    }.getOrDefault(emptyList())

    override fun write(records: List<WearCommandRecord>) {
        val array = JSONArray()
        records.forEach { record -> array.put(JSONObject().apply {
            put("commandId", record.commandId); put("action", record.action.name); put("state", record.state.name)
            record.sanitizedCode?.let { put("code", it) }; record.remoteTripId?.let { put("trip", it) }
            put("created", record.createdAtEpochMs); put("updated", record.updatedAtEpochMs)
        }) }
        preferences.edit().putString(KEY, array.toString()).apply()
    }

    private companion object { const val KEY = "records" }
}
