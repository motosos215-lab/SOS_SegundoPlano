package com.example.sos_segundoplano.data.remote.incident

import com.example.sos_segundoplano.data.remote.auth.AuthNetworkFactory
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EmergencyLocationSharingApiTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    @Test fun postsCanonicalSnapshotContractWithCamelCaseAndMobileAppSource() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody("{\"success\":true,\"data\":{},\"error\":null}"))
        val api = AuthNetworkFactory.createEmergencyLocationSharingApi(server.url("/").toString())

        api.publishSnapshot("Bearer fixture", request())

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/mobile/location-sharing/snapshot", recorded.path)
        assertEquals("Bearer fixture", recorded.getHeader("Authorization"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"incidentId\":\"incident-1\""))
        assertTrue(body.contains("\"clientLocationUpdateId\":\"123e4567-e89b-12d3-a456-426614174000\""))
        assertTrue(body.contains("\"source\":\"MobileApp\""))
        assertTrue(body.contains("\"recordedAtUtc\":\"2026-08-08T14:20:00Z\""))
    }

    private fun request() = EmergencyLocationSnapshotRequestDto(
        incidentId = "incident-1",
        clientLocationUpdateId = "123e4567-e89b-12d3-a456-426614174000",
        latitude = 19.432608,
        longitude = -99.133209,
        accuracyMeters = 15.0,
        altitudeMeters = 2240.0,
        speedMetersPerSecond = 3.2,
        headingDegrees = 180.0,
        recordedAtUtc = "2026-08-08T14:20:00Z"
    )
}
