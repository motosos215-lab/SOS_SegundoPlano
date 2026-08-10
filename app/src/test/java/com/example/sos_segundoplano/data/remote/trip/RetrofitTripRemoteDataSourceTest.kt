package com.example.sos_segundoplano.data.remote.trip

import com.example.sos_segundoplano.data.remote.auth.AuthNetworkFactory
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
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
    }
}
