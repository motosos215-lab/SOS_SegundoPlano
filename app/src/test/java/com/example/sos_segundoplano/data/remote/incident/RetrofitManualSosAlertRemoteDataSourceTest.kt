package com.example.sos_segundoplano.data.remote.incident

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

class RetrofitManualSosAlertRemoteDataSourceTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun postsCanonicalCamelCaseManualSosContract() = runBlocking {
        server.enqueue(jsonResponse(200, successBody()))

        source().createManualSosAlert("Bearer fixture-token", request())

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/mobile/sos-alerts", recorded.path)
        assertEquals("Bearer fixture-token", recorded.getHeader("Authorization"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"tripId\":\"remote-trip-1\""))
        assertTrue(body.contains("\"clientIncidentId\":\"123e4567-e89b-12d3-a456-426614174000\""))
        assertTrue(body.contains("\"clientAlertRequestId\":\"223e4567-e89b-12d3-a456-426614174000\""))
        assertTrue(body.contains("\"incidentType\":\"ManualSos\""))
        assertTrue(body.contains("\"severity\":\"High\""))
        assertTrue(body.contains("\"priority\":\"High\""))
        assertTrue(body.contains("\"reason\":\"ManualSos\""))
        assertTrue(body.contains("\"detectedAtUtc\":\"2026-08-11T15:55:00Z\""))
        assertTrue(body.contains("\"latitude\":19.4326"))
        assertTrue(body.contains("\"longitude\":-99.1332"))
        listOf("score", "confidence", "gpsQuality", "ruleSetVersion", "validationPolicyVersion", "source", "evidenceSummary", "riskLevel").forEach { field ->
            assertFalse(body.contains("\"$field\""))
        }
    }

    @Test fun parsesEmptyPushAndMixedNotificationAttemptsWithoutAssumingOrder() = runBlocking {
        server.enqueue(jsonResponse(200, successBody(attempts = "[]")))
        val empty = source().createManualSosAlert("Bearer fixture-token", request())
        assertEquals(0, (empty as ManualSosAlertSubmissionStatus.Success).notificationAttempts.size)

        server.enqueue(jsonResponse(200, successBody(attempts = MIXED_ATTEMPTS)))
        val mixed = source().createManualSosAlert("Bearer fixture-token", request()) as ManualSosAlertSubmissionStatus.Success
        assertEquals(listOf("Sms", "Push"), mixed.notificationAttempts.map { it.channel })
        assertEquals("Prepared", mixed.notificationAttempts.last().status)
        assertEquals(1, mixed.summary?.pushPrepared)
    }

    @Test fun rejectsInconsistentCanonicalIdsAndAcceptsAnyComplete2xxSuccess() = runBlocking {
        val cases = listOf(
            successBody(tripId = "other-trip") to ManualSosAlertSubmissionStatus.InvalidResponse("incident_trip_id_mismatch"),
            successBody(dispatchIncidentId = "other-incident") to ManualSosAlertSubmissionStatus.InvalidResponse("dispatch_incident_id_mismatch")
        )
        cases.forEach { (body, expected) ->
            server.enqueue(jsonResponse(200, body))
            assertEquals(expected, source().createManualSosAlert("Bearer fixture-token", request()))
        }
        server.enqueue(jsonResponse(201, successBody()))
        assertTrue(source().createManualSosAlert("Bearer fixture-token", request()) is ManualSosAlertSubmissionStatus.Success)
        server.enqueue(jsonResponse(202, """{"success":true,"data":null,"error":null}"""))
        assertEquals(
            ManualSosAlertSubmissionStatus.InvalidResponse("response_data_missing"),
            source().createManualSosAlert("Bearer fixture-token", request())
        )
    }

    @Test fun functional4xxAreReturnedOnceWithoutInternalRetry() = runBlocking {
        listOf(
            400 to "validation_error",
            400 to "onboarding_not_ready",
            400 to "trip_not_ready",
            403 to "forbidden"
        ).forEach { (status, code) ->
            server.enqueue(jsonResponse(status, """{"success":false,"data":null,"error":{"code":"$code","message":"fixture"}}"""))
            assertEquals(
                ManualSosAlertSubmissionStatus.HttpError(status, code),
                source().createManualSosAlert("Bearer fixture-token", request())
            )
        }
        assertEquals(4, server.requestCount)
    }

    private fun source(): RetrofitManualSosAlertRemoteDataSource {
        val moshi = AuthNetworkFactory.createMoshi()
        val api = AuthNetworkFactory.createMobileSosAlertsApi(server.url("/").toString(), moshi)
        return RetrofitManualSosAlertRemoteDataSource(api, moshi)
    }

    private fun request() = ManualSosAlertRequestDto(
        tripId = "remote-trip-1",
        clientIncidentId = "123e4567-e89b-12d3-a456-426614174000",
        clientAlertRequestId = "223e4567-e89b-12d3-a456-426614174000",
        incidentType = "ManualSos",
        severity = "High",
        detectedAtUtc = "2026-08-11T15:55:00Z",
        latitude = 19.4326,
        longitude = -99.1332,
        priority = "High",
        reason = "ManualSos",
        notes = "Alerta SOS desde Android"
    )

    private fun successBody(
        tripId: String = "remote-trip-1",
        dispatchIncidentId: String = "incident-fixture-1",
        attempts: String = MIXED_ATTEMPTS
    ): String = """
        {
          "success": true,
          "data": {
            "incident": {"id":"incident-fixture-1","tripId":"$tripId","status":"Open","incidentType":"ManualSos","severity":"High"},
            "alertDispatch": {"id":"dispatch-fixture-1","incidentId":"$dispatchIncidentId","status":"PendingDispatch","contactsCount":2},
            "notificationAttempts": $attempts,
            "summary": {"pushPrepared":1,"smsPrepared":1,"emailPrepared":0,"totalPrepared":2}
          },
          "error": null
        }
    """.trimIndent()

    private fun jsonResponse(code: Int, body: String): MockResponse = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private companion object {
        const val MIXED_ATTEMPTS = """[
          {"id":"attempt-sms","alertDispatchId":"dispatch-fixture-1","incidentId":"incident-fixture-1","tripId":"remote-trip-1","emergencyContactId":"contact-fixture-1","contactFullName":"Contacto Fixture","channel":"Sms","status":"Prepared","provider":"None"},
          {"id":"attempt-push","alertDispatchId":"dispatch-fixture-1","incidentId":"incident-fixture-1","tripId":"remote-trip-1","emergencyContactId":"contact-fixture-1","contactFullName":"Contacto Fixture","channel":"Push","status":"Prepared","provider":"None"}
        ]"""
    }
}
