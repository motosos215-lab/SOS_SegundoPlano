package com.example.sos_segundoplano.features.background

import com.example.sos_segundoplano.data.remote.trip.FinishTripRequestDto
import com.example.sos_segundoplano.data.remote.trip.RemoteTripFinisher
import com.example.sos_segundoplano.data.remote.trip.TripMutationResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AccidentTripTerminationCoordinatorTest {
    @Test
    fun persistedIncidentFinishesTheCanonicalTripBeforeStoppingMonitoring() = runBlocking {
        val events = mutableListOf<String>()
        var finishRequests = 0
        val coordinator = AccidentTripTerminationCoordinator(
            remoteTripFinisher = RemoteTripFinisher {
                finishRequests += 1
                events += "remote_finish"
                TripMutationResult.Success("trip-incident-1", "Finished")
            },
            hasActiveRemoteTrip = { true },
            stopMonitoring = { events += "monitoring_stop" },
            nowUtc = { Instant.parse("2026-08-16T00:00:00Z") }
        )

        assertTrue(coordinator.finishAfterPersistedIncident())
        assertEquals(listOf("remote_finish", "monitoring_stop"), events)

        assertTrue(coordinator.finishAfterPersistedIncident())
        assertEquals(1, finishRequests)
        assertEquals(1, events.count { it == "monitoring_stop" })
    }

    @Test
    fun failedRemoteFinishDoesNotStopMonitoringOrClaimTripIsInactive() = runBlocking {
        var monitoringStops = 0
        val coordinator = AccidentTripTerminationCoordinator(
            remoteTripFinisher = RemoteTripFinisher {
                TripMutationResult.NetworkUnavailable("network_unavailable")
            },
            hasActiveRemoteTrip = { true },
            stopMonitoring = { monitoringStops += 1 }
        )

        assertFalse(coordinator.finishAfterPersistedIncident())
        assertEquals(0, monitoringStops)
    }

    @Test
    fun missingRemoteTripDoesNotStopMonitoringOrClaimTripIsFinished() = runBlocking {
        var remoteFinishes = 0
        var monitoringStops = 0
        val coordinator = AccidentTripTerminationCoordinator(
            remoteTripFinisher = RemoteTripFinisher {
                remoteFinishes += 1
                TripMutationResult.Success("unused", "Finished")
            },
            hasActiveRemoteTrip = { false },
            stopMonitoring = { monitoringStops += 1 }
        )

        assertFalse(coordinator.finishAfterPersistedIncident())
        assertFalse(coordinator.finishAfterPersistedIncident())
        assertEquals(0, remoteFinishes)
        assertEquals(0, monitoringStops)
    }
}
