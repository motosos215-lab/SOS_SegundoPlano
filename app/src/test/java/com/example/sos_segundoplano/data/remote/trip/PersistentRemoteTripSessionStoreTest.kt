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

    @Test fun inMemorySessionKeepsRemoteTripAndCanonicalStartedAtTogether() {
        val store = InMemoryRemoteTripSessionStore()
        assertTrue(store.setActiveSession("remote-trip-1", 1_723_766_400_000L))
        assertEquals("remote-trip-1", store.remoteTripId.value)
        assertEquals(1_723_766_400_000L, store.startedAtEpochMs.value)
    }

    @Test fun sameRemoteTripKeepsItsFirstConfirmedStartedAt() {
        val store = InMemoryRemoteTripSessionStore()

        assertTrue(store.setActiveSession("remote-trip-1", 1_723_766_400_000L))
        assertTrue(store.setStartedAtEpochMs(1_723_766_500_000L))

        assertEquals("remote-trip-1", store.remoteTripId.value)
        assertEquals(1_723_766_400_000L, store.startedAtEpochMs.value)
    }

    @Test fun sameRemoteTripWithTheSameStartedAtRemainsStable() {
        val store = InMemoryRemoteTripSessionStore()

        assertTrue(store.setActiveSession("remote-trip-1", 1_723_766_400_000L))
        assertTrue(store.setStartedAtEpochMs(1_723_766_400_000L))

        assertEquals(1_723_766_400_000L, store.startedAtEpochMs.value)
    }

    @Test fun legacySessionCanBeCompletedOnceButNotReplaced() {
        val store = InMemoryRemoteTripSessionStore()

        assertTrue(store.setActiveSession("remote-trip-1", null))
        assertTrue(store.setStartedAtEpochMs(1_723_766_400_000L))
        assertTrue(store.setStartedAtEpochMs(1_723_766_500_000L))

        assertEquals("remote-trip-1", store.remoteTripId.value)
        assertEquals(1_723_766_400_000L, store.startedAtEpochMs.value)
    }

    @Test fun newRemoteTripReplacesTheEntireSession() {
        val store = InMemoryRemoteTripSessionStore()

        assertTrue(store.setActiveSession("remote-trip-1", 1_723_766_400_000L))
        assertTrue(store.setActiveSession("remote-trip-2", 1_723_766_500_000L))

        assertEquals("remote-trip-2", store.remoteTripId.value)
        assertEquals(1_723_766_500_000L, store.startedAtEpochMs.value)
    }

    @Test fun persistentSessionRestoresCanonicalStartedAtWithoutRegeneratingIt() {
        val persistence = FakeRemoteTripSessionPersistence()
        val first = PersistentRemoteTripSessionStore(persistence, RemoteTripSessionClock { 1234L })
        first.setActiveSession("remote-trip-1", 1_723_766_400_000L)

        val restored = PersistentRemoteTripSessionStore(persistence)
        assertEquals("remote-trip-1", restored.remoteTripId.value)
        assertEquals(1_723_766_400_000L, restored.startedAtEpochMs.value)
    }

    @Test fun clearRemovesRemoteTripAndStartedAtTogether() {
        val store = PersistentRemoteTripSessionStore(FakeRemoteTripSessionPersistence())
        store.setActiveSession("remote-trip-1", 1_723_766_400_000L)

        assertTrue(store.clearRemoteTripId())
        assertNull(store.remoteTripId.value)
        assertNull(store.startedAtEpochMs.value)
    }

    @Test fun legacyPersistedSessionWithoutStartedAtRemainsReadableWithoutInventingOne() {
        val store = PersistentRemoteTripSessionStore(
            FakeRemoteTripSessionPersistence(value = PersistedRemoteTripSession("remote-trip-1", 1234L))
        )
        assertEquals("remote-trip-1", store.remoteTripId.value)
        assertNull(store.startedAtEpochMs.value)
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
