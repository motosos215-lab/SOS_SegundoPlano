package com.example.sos_segundoplano.data.remote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.sos_segundoplano.data.remote.incident.RemoteIncidentLink
import com.example.sos_segundoplano.data.remote.incident.RemoteIncidentSyncState
import com.example.sos_segundoplano.data.remote.incident.SharedPreferencesRemoteIncidentLinkStore
import com.example.sos_segundoplano.data.remote.trip.PersistentRemoteTripSessionStore
import com.example.sos_segundoplano.data.remote.trip.RemoteTripSessionClock
import com.example.sos_segundoplano.data.remote.trip.SharedPreferencesRemoteTripSessionPersistence
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteLifecyclePersistenceInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After fun tearDown() {
        context.getSharedPreferences(TRIP_PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(INCIDENT_PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun remoteTripIdSurvivesStoreRecreation() {
        val first = PersistentRemoteTripSessionStore(
            persistence = SharedPreferencesRemoteTripSessionPersistence(context, TRIP_PREFERENCES),
            clock = RemoteTripSessionClock { 1234L }
        )

        assertTrue(first.setRemoteTripId("remote-trip-1"))

        val restored = PersistentRemoteTripSessionStore(
            SharedPreferencesRemoteTripSessionPersistence(context, TRIP_PREFERENCES)
        )
        assertEquals("remote-trip-1", restored.remoteTripId.value)
    }

    @Test fun remoteIncidentIdAndClientIncidentIdSurviveStoreRecreation() {
        val first = SharedPreferencesRemoteIncidentLinkStore(context, INCIDENT_PREFERENCES)
        val link = RemoteIncidentLink(
            localIncidentId = 7L,
            clientIncidentId = "client-incident-id",
            remoteTripId = "remote-trip-1",
            remoteIncidentId = "remote-incident-1",
            syncState = RemoteIncidentSyncState.Created,
            updatedAtEpochMillis = 1234L
        )

        assertTrue(first.save(link))

        val restored = SharedPreferencesRemoteIncidentLinkStore(context, INCIDENT_PREFERENCES)
            .read("client-incident-id")
        assertEquals(link, restored)
    }

    @Test fun pendingIncidentPersistsBeforeRemoteTripIsAvailable() {
        val first = SharedPreferencesRemoteIncidentLinkStore(context, INCIDENT_PREFERENCES)
        val pending = RemoteIncidentLink(
            localIncidentId = 8L,
            clientIncidentId = "pending-client-incident-id",
            remoteTripId = null,
            remoteIncidentId = null,
            syncState = RemoteIncidentSyncState.Pending,
            updatedAtEpochMillis = 2345L
        )

        assertTrue(first.save(pending))

        val restored = SharedPreferencesRemoteIncidentLinkStore(context, INCIDENT_PREFERENCES)
            .read("pending-client-incident-id")
        assertEquals(pending, restored)
    }

    private companion object {
        const val TRIP_PREFERENCES = "remote_trip_session_test"
        const val INCIDENT_PREFERENCES = "remote_incident_links_test"
    }
}
