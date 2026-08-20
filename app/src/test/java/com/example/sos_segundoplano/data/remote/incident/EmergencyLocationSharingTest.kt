package com.example.sos_segundoplano.data.remote.incident

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyLocationSharingTest {
    @Test fun secondaryLocationPublicationTimeoutDoesNotTurnCreatedSosIntoFailure() = runTest {
        var started = false
        val publisher = EmergencyLocationPublisher {
            started = true
            delay(60_000L)
            EmergencyLocationPublicationResult.Published
        }

        publisher.publishSafely(snapshot(), timeoutMillis = 100L)

        assertTrue(started)
    }

    private fun snapshot() = EmergencyLocationSnapshotRequestDto(
        incidentId = "incident-1",
        clientLocationUpdateId = "11111111-1111-1111-1111-111111111111",
        latitude = 19.4326,
        longitude = -99.1332,
        recordedAtUtc = "2026-08-18T16:00:00Z"
    )
}
