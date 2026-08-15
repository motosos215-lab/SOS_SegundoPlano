package com.example.sos_segundoplano.data.remote.history

import com.example.sos_segundoplano.data.remote.auth.AuthNetworkFactory
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RiderHistoryApiTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    @Test fun tripsListParsesTheConfirmedPagedDataWrapper() = runBlocking {
        server.enqueue(json("""{"success":true,"data":{"trips":[{"id":"trip-1","status":"Finished","startedAtUtc":"2026-08-12T16:00:00Z","finishedAtUtc":"2026-08-12T17:00:00Z","startLocation":{"latitude":19.4326,"longitude":-99.1332,"accuracyMeters":12.5,"provider":"gps","recordedAtUtc":"2026-08-12T16:00:00Z"},"endLocation":{"latitude":19.435,"longitude":-99.136,"accuracyMeters":10.0,"provider":"gps","recordedAtUtc":"2026-08-12T17:00:00Z"},"vehicleId":"ignored"}],"pageNumber":1,"pageSize":20,"totalCount":17},"error":null}"""))

        val response = AuthNetworkFactory.createTripsApi(server.url("/").toString()).listTrips("Bearer fixture")

        assertTrue(response.isSuccessful)
        assertEquals("Finished", response.body()?.data?.trips?.single()?.status)
        assertEquals(17, response.body()?.data?.totalCount)
        assertEquals(19.4326, response.body()?.data?.trips?.single()?.startLocation?.latitude)
        assertEquals(-99.136, response.body()?.data?.trips?.single()?.endLocation?.longitude)
        assertEquals("gps", response.body()?.data?.trips?.single()?.startLocation?.provider)
        assertEquals("/api/v1/trips", server.takeRequest().path)
    }

    @Test fun tripsListToleratesMissingPageItems() = runBlocking {
        server.enqueue(json("""{"success":true,"data":{"pageNumber":1,"pageSize":20,"totalCount":0},"error":null}"""))

        val response = AuthNetworkFactory.createTripsApi(server.url("/").toString()).listTrips("Bearer fixture")

        assertTrue(response.body()?.data?.trips.isNullOrEmpty())
    }

    @Test fun incidentsListParsesTheConfirmedPagedDataWrapper() = runBlocking {
        server.enqueue(json("""{"success":true,"data":{"incidents":[{"id":"incident-1","tripId":"trip-1","cause":"ManualSos","riskLevel":"High","status":"Open","occurredAtUtc":"2026-08-12T16:30:00Z","mobileDeviceId":"ignored"},{"id":"incident-2","cause":"CountdownTimeout","riskLevel":"Unknown","status":"Open","occurredAtUtc":"2026-08-12T16:31:00Z"}],"pageNumber":1,"pageSize":20,"totalCount":2},"error":null}"""))

        val response = AuthNetworkFactory.createIncidentsApi(server.url("/").toString()).listIncidents("Bearer fixture")

        assertEquals("ManualSos", response.body()?.data?.incidents?.get(0)?.cause)
        assertEquals("High", response.body()?.data?.incidents?.get(0)?.riskLevel)
        assertEquals("CountdownTimeout", response.body()?.data?.incidents?.get(1)?.cause)
        assertEquals("Unknown", response.body()?.data?.incidents?.get(1)?.riskLevel)
        assertEquals("/api/v1/incidents", server.takeRequest().path)
    }

    @Test fun incidentsListToleratesEmptyAndMissingPageItems() = runBlocking {
        server.enqueue(json("""{"success":true,"data":{"incidents":[],"pageNumber":1,"pageSize":20,"totalCount":0},"error":null}"""))
        server.enqueue(json("""{"success":true,"data":{"pageNumber":1,"pageSize":20,"totalCount":0},"error":null}"""))
        val api = AuthNetworkFactory.createIncidentsApi(server.url("/").toString())

        assertTrue(api.listIncidents("Bearer fixture").body()?.data?.incidents.isNullOrEmpty())
        assertTrue(api.listIncidents("Bearer fixture").body()?.data?.incidents.isNullOrEmpty())
    }

    private fun json(body: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)
}
