package com.example.sos_segundoplano.data.remote.incident

import com.example.sos_segundoplano.data.remote.auth.AuthNetworkFactory
import com.example.sos_segundoplano.domain.validation.IncidentRemoteCreationStatus
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RetrofitIncidentRemoteDataSourceTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun usesPostIncidentsPathBearerHeaderAndContractBody() = runBlocking {
        server.enqueue(jsonResponse(201, SUCCESS_BODY))

        val result = source().createIncident("Bearer token-de-prueba", request())

        assertEquals(IncidentRemoteCreationStatus.Success("incident-remote-1"), result)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/incidents", recorded.path)
        assertEquals("Bearer token-de-prueba", recorded.getHeader("Authorization"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"tripId\":\"1\""))
        assertTrue(body.contains("\"clientIncidentId\":\"mobile-1-2-3\""))
        assertTrue(body.contains("\"source\":\"MobileDetection\""))
        assertTrue(body.contains("\"cause\":\"CountdownTimeout\""))
        assertTrue(body.contains("\"riskLevel\":\"High\""))
        assertTrue(body.contains("\"score\":75"))
        assertTrue(body.contains("\"confidence\":0.8"))
        assertTrue(body.contains("\"gpsQuality\":\"Good\""))
        assertTrue(body.contains("\"ruleSetVersion\":\"local-rules-v1\""))
        assertTrue(body.contains("\"validationPolicyVersion\":\"false-positive-validation-v1\""))
        assertTrue(body.contains("\"occurredAtUtc\":\"2026-08-08T14:20:00Z\""))
    }

    @Test fun mapsHttpAndInvalidResponsesClearly() = runBlocking {
        val cases = listOf(
            jsonResponse(400, """{"success":false,"data":null,"error":{"code":"validation_error","message":"raw"}}""") to
                IncidentRemoteCreationStatus.HttpError(400, "validation_error"),
            jsonResponse(200, """{"success":true,"data":null,"error":null}""") to
                IncidentRemoteCreationStatus.InvalidResponse("incident_id_missing"),
            jsonResponse(200, "{") to
                IncidentRemoteCreationStatus.InvalidResponse("response_body_invalid"),
            MockResponse().setResponseCode(500) to
                IncidentRemoteCreationStatus.HttpError(500, "request_rejected")
        )
        cases.forEachIndexed { index, (response, expected) ->
            server.enqueue(response)
            assertEquals("case $index", expected, source().createIncident("Bearer token-de-prueba", request()))
        }
    }

    private fun source(): RetrofitIncidentRemoteDataSource {
        val moshi = AuthNetworkFactory.createMoshi()
        val api = AuthNetworkFactory.createIncidentsApi(server.url("/").toString(), moshi)
        return RetrofitIncidentRemoteDataSource(api, moshi)
    }

    private fun request(): CreateIncidentRequestDto = CreateIncidentRequestDto(
        tripId = "1",
        clientIncidentId = "mobile-1-2-3",
        source = "MobileDetection",
        cause = "CountdownTimeout",
        riskLevel = "High",
        score = 75,
        confidence = 0.8,
        gpsQuality = "Good",
        ruleSetVersion = "local-rules-v1",
        validationPolicyVersion = "false-positive-validation-v1",
        occurredAtUtc = "2026-08-08T14:20:00Z"
    )

    private fun jsonResponse(code: Int, body: String): MockResponse = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private companion object {
        const val SUCCESS_BODY = """
            {
              "success": true,
              "data": {
                "incidentId": "incident-remote-1"
              },
              "error": null
            }
        """
    }
}
