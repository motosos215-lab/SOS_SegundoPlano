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
        server.enqueue(jsonResponse(200, REAL_SUCCESS_BODY))

        val result = source().createIncident("Bearer token-de-prueba", request())

        assertEquals(IncidentRemoteCreationStatus.Success("6a792b116024837a842685c7"), result)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/incidents", recorded.path)
        assertEquals("Bearer token-de-prueba", recorded.getHeader("Authorization"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"tripId\":\"remote-trip-1\""))
        assertTrue(body.contains("\"clientIncidentId\":\"123e4567-e89b-12d3-a456-426614174000\""))
        assertTrue(body.contains("\"source\":\"MobileDetection\""))
        assertTrue(body.contains("\"cause\":\"CountdownTimeout\""))
        assertTrue(body.contains("\"riskLevel\":\"High\""))
        assertTrue(body.contains("\"assessmentId\":2"))
        assertTrue(body.contains("\"windowId\":3"))
        assertTrue(body.contains("\"hasLocation\":false"))
        assertTrue(body.contains("\"occurredAtUtc\":\"2026-08-08T14:20:00Z\""))
    }

    @Test fun parsesRealSuccessEnvelopeWithNullableIncidentFields() = runBlocking {
        server.enqueue(jsonResponse(200, REAL_SUCCESS_BODY))

        val result = source().createIncident("Bearer token-de-prueba", request())

        assertEquals(IncidentRemoteCreationStatus.Success("6a792b116024837a842685c7"), result)
    }

    @Test fun parsesSuccessWhenOptionalIncidentFieldsAreAbsentOrNull() = runBlocking {
        val cases = listOf(
            SUCCESS_WITH_NULL_OPTIONALS,
            SUCCESS_WITH_ABSENT_OPTIONALS,
            SUCCESS_WITH_NULL_SCORE_AND_CONFIDENCE
        )
        cases.forEach { body ->
            server.enqueue(jsonResponse(201, body))
            assertEquals(IncidentRemoteCreationStatus.Success("incident-id"), source().createIncident("Bearer token-de-prueba", request()))
        }
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
        tripId = "remote-trip-1",
        clientIncidentId = "123e4567-e89b-12d3-a456-426614174000",
        source = "MobileDetection",
        cause = "CountdownTimeout",
        riskLevel = "High",
        occurredAtUtc = "2026-08-08T14:20:00Z",
        evidenceSummary = IncidentEvidenceSummaryDto(
            assessmentId = 2L,
            windowId = 3L,
            triggeredRules = emptyList(),
            hasLocation = false
        )
    )

    private fun jsonResponse(code: Int, body: String): MockResponse = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private companion object {
        const val REAL_SUCCESS_BODY = """
            {
              "success": true,
              "data": {
                "incident": {
                  "id": "6a792b116024837a842685c7",
                  "tripId": "6a791a5e6024837a842685a5",
                  "vehicleId": "6a7918c06024837a8426857a",
                  "mobileDeviceId": "6a7919df6024837a84268585",
                  "smartwatchDeviceId": null,
                  "source": "MobileDetection",
                  "cause": "CountdownTimeout",
                  "riskLevel": "High",
                  "status": "Open",
                  "score": 75,
                  "confidence": 0.8,
                  "occurredAtUtc": "2026-08-09T23:59:00Z",
                  "createdAtUtc": "2026-08-09T23:59:01Z",
                  "updatedAtUtc": "2026-08-09T23:59:01Z",
                  "cancelledAtUtc": null,
                  "closedAtUtc": null,
                  "closureReason": null,
                  "closureNotes": null
                }
              },
              "error": null
            }
        """

        const val SUCCESS_WITH_NULL_OPTIONALS = """
            {
              "success": true,
              "data": {
                "incident": {
                  "id": "incident-id",
                  "tripId": "remote-trip-1",
                  "smartwatchDeviceId": null,
                  "score": 75,
                  "confidence": 0.8,
                  "cancelledAtUtc": null,
                  "closedAtUtc": null,
                  "closureReason": null,
                  "closureNotes": null
                }
              },
              "error": null
            }
        """

        const val SUCCESS_WITH_ABSENT_OPTIONALS = """
            {
              "success": true,
              "data": {
                "incident": {
                  "id": "incident-id",
                  "tripId": "remote-trip-1"
                }
              },
              "error": null
            }
        """

        const val SUCCESS_WITH_NULL_SCORE_AND_CONFIDENCE = """
            {
              "success": true,
              "data": {
                "incident": {
                  "id": "incident-id",
                  "tripId": "remote-trip-1",
                  "score": null,
                  "confidence": null
                }
              },
              "error": null
            }
        """
    }
}
