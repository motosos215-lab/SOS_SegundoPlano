package com.example.sos_segundoplano.data.remote.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentRemoteTripSessionStoreTest {
    @Test fun successfulSaveSurvivesStoreRecreation() {
        val persistence = FakeRemoteTripSessionPersistence()
        val first = PersistentRemoteTripSessionStore(persistence, RemoteTripSessionClock { 1234L })

        assertTrue(first.setRemoteTripId(" remote-trip-1 "))

        val restored = PersistentRemoteTripSessionStore(persistence)
        assertEquals("remote-trip-1", restored.remoteTripId.value)
        assertEquals(PersistedRemoteTripSession("remote-trip-1", 1234L), persistence.value)
    }

    @Test fun failedPersistenceDoesNotExposeUndurableTripId() {
        val persistence = FakeRemoteTripSessionPersistence(saveSucceeds = false)
        val store = PersistentRemoteTripSessionStore(persistence)

        assertFalse(store.setRemoteTripId("remote-trip-1"))
        assertNull(store.remoteTripId.value)
    }

    @Test fun failedClearKeepsCurrentTripForLaterReconciliation() {
        val persistence = FakeRemoteTripSessionPersistence(clearSucceeds = false)
        val store = PersistentRemoteTripSessionStore(persistence)
        assertTrue(store.setRemoteTripId("remote-trip-1"))

        assertFalse(store.clearRemoteTripId())
        assertEquals("remote-trip-1", store.remoteTripId.value)
    }

    @Test fun corruptPersistedSessionIsRejectedAndCleared() {
        val persistence = FakeRemoteTripSessionPersistence(
            value = PersistedRemoteTripSession(" ", -1L)
        )

        val store = PersistentRemoteTripSessionStore(persistence)

        assertNull(store.remoteTripId.value)
        assertEquals(1, persistence.clearCalls)
    }
}

private class FakeRemoteTripSessionPersistence(
    var value: PersistedRemoteTripSession? = null,
    private val saveSucceeds: Boolean = true,
    private val clearSucceeds: Boolean = true
) : RemoteTripSessionPersistence {
    var clearCalls = 0

    override fun read(): PersistedRemoteTripSession? = value

    override fun save(session: PersistedRemoteTripSession): Boolean {
        if (saveSucceeds) value = session
        return saveSucceeds
    }

    override fun clear(): Boolean {
        clearCalls++
        if (clearSucceeds) value = null
        return clearSucceeds
    }
}
