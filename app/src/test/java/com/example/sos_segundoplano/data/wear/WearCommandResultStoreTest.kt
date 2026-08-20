package com.example.sos_segundoplano.data.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WearCommandResultStoreTest {

    @Test
    fun saveAndReadPreservesStateCodeAndTripId() {
        val store = store()
        val record = record(
            commandId = "command-start-001",
            action = WearCommandAction.StartTrip,
            state = WearCommandState.TerminalSuccess,
            sanitizedCode = "action_not_available",
            remoteTripId = "trip-real-123",
        )

        store.save(record)

        assertEquals(record, store.find("command-start-001", WearCommandAction.StartTrip))
    }

    @Test
    fun nullableRemoteTripIdRoundTripsAsNull() {
        val store = store()
        store.save(record(remoteTripId = null))

        assertNull(store.find("command-001", WearCommandAction.StartTrip)?.remoteTripId)
    }

    @Test
    fun sameActionAndCommandReplacesWithLatestRecord() {
        val store = store()
        store.save(record(state = WearCommandState.Retryable, sanitizedCode = "retry_later", updatedAtEpochMs = 10L))
        val replacement = record(
            state = WearCommandState.TerminalRejected,
            sanitizedCode = "action_not_available",
            remoteTripId = "trip-real-123",
            createdAtEpochMs = 20L,
            updatedAtEpochMs = 20L,
        )

        store.save(replacement)

        assertEquals(replacement, store.find("command-001", WearCommandAction.StartTrip))
    }

    @Test
    fun sameCommandIdAcrossActionsDoesNotCollide() {
        val store = store()
        store.save(record(commandId = "command-shared-001", action = WearCommandAction.StartTrip, state = WearCommandState.TerminalSuccess))
        store.save(record(commandId = "command-shared-001", action = WearCommandAction.FinishTrip, state = WearCommandState.PartialSideEffect))

        assertEquals(WearCommandState.TerminalSuccess, store.find("command-shared-001", WearCommandAction.StartTrip)?.state)
        assertEquals(WearCommandState.PartialSideEffect, store.find("command-shared-001", WearCommandAction.FinishTrip)?.state)
    }

    @Test
    fun unknownCommandReturnsMissing() {
        assertNull(store().find("unknown-command", WearCommandAction.ManualSos))
    }

    @Test
    fun entryAtTtlBoundaryRemainsAvailableAndAfterTtlExpires() {
        var now = 1_000L
        val store = store(clock = { now })
        store.save(record(updatedAtEpochMs = 1_000L))

        now = 1_000L + TTL_MILLIS
        assertEquals("command-001", store.find("command-001", WearCommandAction.StartTrip)?.commandId)

        now += 1L
        assertNull(store.find("command-001", WearCommandAction.StartTrip))
    }

    @Test
    fun storePrunesOldestRecordsWhenMaximumEntryCountIsExceeded() {
        val storage = InMemoryWearCommandRecordStorage()
        val store = store(storage = storage, clock = { 1_000L + MAX_ENTRIES })

        repeat(MAX_ENTRIES + 1) { index ->
            store.save(record(commandId = "command-$index", createdAtEpochMs = 1_000L + index, updatedAtEpochMs = 1_000L + index))
        }

        assertEquals(MAX_ENTRIES, storage.records.size)
        assertNull(store.find("command-0", WearCommandAction.StartTrip))
        assertEquals("command-1", store.find("command-1", WearCommandAction.StartTrip)?.commandId)
        assertEquals("command-$MAX_ENTRIES", store.find("command-$MAX_ENTRIES", WearCommandAction.StartTrip)?.commandId)
    }

    private fun store(
        storage: InMemoryWearCommandRecordStorage = InMemoryWearCommandRecordStorage(),
        clock: () -> Long = { 100L },
    ) = SharedPreferencesWearCommandResultStore(storage, clock)

    private fun record(
        commandId: String = "command-001",
        action: WearCommandAction = WearCommandAction.StartTrip,
        state: WearCommandState = WearCommandState.TerminalSuccess,
        sanitizedCode: String? = null,
        remoteTripId: String? = "trip-real-123",
        createdAtEpochMs: Long = 100L,
        updatedAtEpochMs: Long = 100L,
    ) = WearCommandRecord(commandId, action, state, sanitizedCode, remoteTripId, createdAtEpochMs, updatedAtEpochMs)

    private class InMemoryWearCommandRecordStorage : WearCommandRecordStorage {
        var records: List<WearCommandRecord> = emptyList()
        override fun read(): List<WearCommandRecord> = records
        override fun write(records: List<WearCommandRecord>) {
            this.records = records
        }
    }

    private companion object {
        const val MAX_ENTRIES = 48
        const val TTL_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}
