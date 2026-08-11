package com.example.sos_segundoplano.data.remote.trip

import com.example.sos_segundoplano.data.remote.auth.AuthNetworkFactory
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RetrofitTripRemoteDataSourceTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun activeTripSuccessUsesGetPathBearerHeaderAndReturnsRemoteTripId() = runBlocking {
        server.enqueue(jsonResponse(200, SUCCESS_BODY))

        val result = source().activeTrip("Bearer token-de-prueba")

        assertEquals(ActiveTripLookupResult.Found("remote-trip-1"), result)
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/v1/trips/active", recorded.path)
        assertEquals("Bearer token-de-prueba", recorded.getHeader("Authorization"))
        assertEquals(0L, recorded.bodySize)
    }

    @Test fun activeTripNoDataOrNotFoundMapsToNoActiveTrip() = runBlocking {
        val cases = listOf(
            jsonResponse(200, """{"success":true,"data":null,"error":null}"""),
            MockResponse().setResponseCode(404)
        )
        cases.forEach { response ->
            server.enqueue(response)
            assertEquals(ActiveTripLookupResult.NoActiveTrip, source().activeTrip("Bearer token-de-prueba"))
        }
    }

    @Test fun activeTripHttpAndInvalidResponsesAreControlled() = runBlocking {
        val cases = listOf(
            jsonResponse(500, """{"success":false,"data":null,"error":{"code":"server_error","message":"raw"}}""") to
                ActiveTripLookupResult.HttpError(500, "server_error"),
            jsonResponse(200, "{") to
                ActiveTripLookupResult.InvalidResponse("response_body_invalid")
        )
        cases.forEach { (response, expected) ->
            server.enqueue(response)
            assertEquals(expected, source().activeTrip("Bearer token-de-prueba"))
        }
    }

    @Test fun startTripUsesRequiredBackendIdsAndParsesCanonicalTrip() = runBlocking {
        server.enqueue(jsonResponse(200, START_SUCCESS_BODY))
        val request = StartTripRequestDto(
            vehicleId = "vehicle-fixture-1",
            mobileDeviceId = "mobile-device-fixture-1",
            smartwatchDeviceId = null
        )

        val result = source().startTrip("Bearer token-de-prueba", request)

        assertEquals(TripMutationResult.Success("remote-trip-1", "Active"), result)
        val recorded = server.takeRequest()
        val body = recorded.body.readUtf8()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/trips/start", recorded.path)
        assertEquals("Bearer token-de-prueba", recorded.getHeader("Authorization"))
        assertTrue(body.contains("\"vehicleId\":\"vehicle-fixture-1\""))
        assertTrue(body.contains("\"mobileDeviceId\":\"mobile-device-fixture-1\""))
        assertFalse(body.contains("smartwatchDeviceId"))
        assertFalse(body.contains("clientTripId"))
    }

    @Test fun vehiclesAndDevicesParseConfirmedWrappersAndUseAuthenticatedGetPaths() = runBlocking {
        server.enqueue(jsonResponse(200, VEHICLES_BODY))
        assertEquals(
            TripStartResourceLookupResult.Success(
                listOf(VehicleResourceDto("vehicle-fixture-1", "Completed", true, true))
            ),
            source().vehicles("Bearer token-de-prueba")
        )
        val vehiclesRequest = server.takeRequest()
        assertEquals("GET", vehiclesRequest.method)
        assertEquals("/api/v1/vehicles", vehiclesRequest.path)
        assertEquals("Bearer token-de-prueba", vehiclesRequest.getHeader("Authorization"))

        server.enqueue(jsonResponse(200, DEVICES_BODY))
        assertEquals(
            TripStartResourceLookupResult.Success(
                listOf(DeviceResourceDto("mobile-fixture-1", "MobileApp", "Linked", null, true, true))
            ),
            source().devices("Bearer token-de-prueba")
        )
        val devicesRequest = server.takeRequest()
        assertEquals("GET", devicesRequest.method)
        assertEquals("/api/v1/devices", devicesRequest.path)
        assertEquals("Bearer token-de-prueba", devicesRequest.getHeader("Authorization"))
    }

    @Test fun startConflictAndFinishEmptyBodyAreMappedWithoutInventedRetry() = runBlocking {
        server.enqueue(jsonResponse(409, """{"success":false,"data":null,"error":{"code":"active_trip_exists","message":"fixture"}}"""))
        assertEquals(
            TripMutationResult.HttpError(409, "active_trip_exists"),
            source().startTrip("Bearer token-de-prueba", StartTripRequestDto("vehicle-fixture-1", "mobile-device-fixture-1"))
        )
        server.takeRequest()

        server.enqueue(jsonResponse(200, FINISH_SUCCESS_BODY))
        assertEquals(
            TripMutationResult.Success("remote-trip-1", "Finished"),
            source().finishTrip("Bearer token-de-prueba", "remote-trip-1", FinishTripRequestDto())
        )
        val finish = server.takeRequest()
        assertEquals("POST", finish.method)
        assertEquals("/api/v1/trips/remote-trip-1/finish", finish.path)
        assertEquals("{}", finish.body.readUtf8())
    }

    private fun source(): RetrofitTripRemoteDataSource {
        val moshi = AuthNetworkFactory.createMoshi()
        val api = AuthNetworkFactory.createTripsApi(server.url("/").toString(), moshi)
        return RetrofitTripRemoteDataSource(api, moshi)
    }

    private fun jsonResponse(code: Int, body: String): MockResponse = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private companion object {
        const val SUCCESS_BODY = """
            {
              "success": true,
              "data": {
                "trip": {
                  "id": "remote-trip-1",
                  "status": "Active"
                }
              },
              "error": null
            }
        """

        const val START_SUCCESS_BODY = """
            {"success":true,"data":{"trip":{"id":"remote-trip-1","status":"Active"}},"error":null}
        """

        const val FINISH_SUCCESS_BODY = """
            {"success":true,"data":{"trip":{"id":"remote-trip-1","status":"Finished"}},"error":null}
        """

        const val VEHICLES_BODY = """
            {"success":true,"data":{"vehicles":[{"id":"vehicle-fixture-1","completionStatus":"Completed","isPrimary":true,"isActive":true}]},"error":null}
        """

        const val DEVICES_BODY = """
            {"success":true,"data":{"devices":[{"id":"mobile-fixture-1","deviceType":"MobileApp","linkStatus":"Linked","isPrimary":true,"isActive":true}]},"error":null}
        """
    }
}
