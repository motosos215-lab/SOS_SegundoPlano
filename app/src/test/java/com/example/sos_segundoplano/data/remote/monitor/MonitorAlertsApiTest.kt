package com.example.sos_segundoplano.data.remote.monitor

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

class MonitorAlertsApiTest {
    private lateinit var server: MockWebServer
    @Before fun setup() { server = MockWebServer(); server.start() }
    @After fun teardown() { server.shutdown() }

    @Test fun usesNotificationDeliveryAttemptIdForEveryRoute() = runBlocking {
        server.enqueue(json("""{"success":true,"data":{"alerts":[],"pageNumber":1,"pageSize":20,"totalCount":0},"error":null}"""))
        repeat(6) { server.enqueue(json("""{"success":true,"data":{},"error":null}""")) }
        val api = api()
        api.list("Bearer monitor")
        api.detail("Bearer monitor", "attempt-1")
        api.status("Bearer monitor", "attempt-1")
        api.location("Bearer monitor", "attempt-1")
        api.view("Bearer monitor", "attempt-1")
        api.acknowledge("Bearer monitor", "attempt-1", AcknowledgeMonitorAlertRequestDto("CanAssist", "Available"))
        api.decline("Bearer monitor", "attempt-1", DeclineMonitorAlertRequestDto(reason = "Unavailable"))
        assertEquals("/api/v1/monitor/alerts", server.takeRequest().path)
        assertEquals("/api/v1/monitor/alerts/attempt-1", server.takeRequest().path)
        assertEquals("/api/v1/monitor/alerts/attempt-1/status", server.takeRequest().path)
        assertEquals("/api/v1/monitor/alerts/attempt-1/location", server.takeRequest().path)
        assertEquals("/api/v1/monitor/alerts/attempt-1/view", server.takeRequest().path)
        val acknowledge = server.takeRequest()
        val decline = server.takeRequest()
        assertEquals("/api/v1/monitor/alerts/attempt-1/acknowledge", acknowledge.path)
        assertTrue(acknowledge.body.readUtf8().contains("\"responseType\":\"CanAssist\""))
        assertEquals("/api/v1/monitor/alerts/attempt-1/decline", decline.path)
        val declineBody = decline.body.readUtf8()
        assertTrue(declineBody.contains("\"reason\":\"Unavailable\""))
        assertTrue(!declineBody.contains("responseType"))
        assertTrue(!declineBody.contains("message"))
    }

    @Test fun parsesTheProductionHistoryPageShape() = runBlocking {
        server.enqueue(json(historyFixture))

        val response = api().list("Bearer monitor")
        val page = response.body()?.data

        assertTrue(response.isSuccessful)
        assertEquals(2, page?.alerts?.size)
        assertEquals(1, page?.pageNumber)
        assertEquals(20, page?.pageSize)
        assertEquals(9, page?.totalCount)
        assertEquals("Acknowledged", page?.alerts?.first()?.status)
        assertEquals("CanAssist", page?.alerts?.first()?.responseType)
        assertEquals("claro", page?.alerts?.first()?.message)
        assertEquals("Pending", page?.alerts?.last()?.status)
        assertEquals("None", page?.alerts?.last()?.responseType)
        assertEquals(null, page?.alerts?.last()?.message)
    }

    @Test fun detailStillParsesItsAcknowledgementObject() = runBlocking {
        server.enqueue(json("""{"success":true,"data":{"acknowledgement":{"notificationDeliveryAttemptId":"attempt-1","status":"Acknowledged","responseType":"CanAssist","message":"claro"}},"error":null}"""))

        val response = api().detail("Bearer monitor", "attempt-1")

        assertTrue(response.isSuccessful)
        assertEquals("attempt-1", response.body()?.data?.acknowledgement?.notificationDeliveryAttemptId)
        assertEquals("CanAssist", response.body()?.data?.acknowledgement?.responseType)
        assertEquals("claro", response.body()?.data?.acknowledgement?.message)
    }

    @Test fun parsesDedicatedMonitorLocationShape() = runBlocking {
        server.enqueue(json("""{"success":true,"data":{"available":true,"incidentId":"incident-1","latitude":19.4326,"longitude":-99.1332,"accuracyMeters":8.0,"source":"MobileApp","isActive":true,"isStale":false},"error":null}"""))

        val location = api().location("Bearer monitor", "attempt-1").body()?.data?.resolvedLocation()

        assertEquals(true, location?.available)
        assertEquals(19.4326, location?.latitude ?: Double.NaN, 0.0)
        assertEquals(-99.1332, location?.longitude ?: Double.NaN, 0.0)
        assertEquals("MobileApp", location?.source)
    }

    @Test fun parsesTheProductionStatusShape() = runBlocking {
        server.enqueue(json(statusFixture))

        val status = api().status("Bearer monitor", "attempt-1").body()?.data

        assertEquals("ManualSos", status?.incident?.cause)
        assertEquals("High", status?.incident?.riskLevel)
        assertEquals("Finished", status?.trip?.status)
        assertEquals("High", status?.alertDispatch?.priority)
        assertEquals("Acknowledged", status?.overallStatus)
        assertEquals(false, status?.requiresAttention)
        assertEquals(false, status?.location?.available)
        assertEquals(null, status?.location?.latitude)
    }

    private fun api() = AuthNetworkFactory.createMonitorAlertsApi(server.url("/").toString())
    private fun json(body: String) = MockResponse().setResponseCode(200).setBody(body)

    private companion object {
        val statusFixture = """
            {
              "success": true,
              "data": {
                "incident": {"id":"incident-1","status":"Open","source":"MobileDetection","cause":"ManualSos","riskLevel":"High","occurredAtUtc":"2026-08-15T21:42:26.405567+00:00","createdAtUtc":"2026-08-15T21:42:27.2102467+00:00"},
                "trip": {"id":"trip-1","status":"Finished","startedAtUtc":"2026-08-15T21:42:08.6871345+00:00","finishedAtUtc":"2026-08-15T21:43:52.9298004+00:00"},
                "alertDispatch": {"id":"dispatch-1","status":"PendingDispatch","priority":"High","reason":"ManualSos","createdAtUtc":"2026-08-15T21:42:27.6576782+00:00"},
                "notifications": {"total":1,"prepared":0,"simulatedSent":1,"failed":0,"cancelled":0},
                "acknowledgements": {"total":1,"pending":0,"viewed":0,"acknowledged":1,"declined":0},
                "location": {"available":false,"incidentId":null,"tripId":null,"latitude":null,"longitude":null,"accuracyMeters":null,"source":null,"recordedAtUtc":null,"receivedAtUtc":null,"isActive":null,"isStale":null},
                "overallStatus": "Acknowledged",
                "requiresAttention": false,
                "lastUpdatedAtUtc": "2026-08-15T21:43:52.9298004+00:00"
              },
              "error": null
            }
        """.trimIndent()

        val historyFixture = """
            {"success":true,"data":{"alerts":[
              {"id":"history-1","alertDispatchId":"dispatch-1","notificationDeliveryAttemptId":"attempt-1","incidentId":"incident-1","tripId":"trip-1","emergencyContactId":"contact-1","status":"Acknowledged","responseType":"CanAssist","message":"claro","viewedAtUtc":"2026-08-15T21:43:08.5600019+00:00","acknowledgedAtUtc":"2026-08-15T21:43:27.3923895+00:00","declinedAtUtc":null,"createdAtUtc":"2026-08-15T21:43:08.034688+00:00","updatedAtUtc":"2026-08-15T21:43:27.3923895+00:00"},
              {"id":"history-2","alertDispatchId":"dispatch-2","notificationDeliveryAttemptId":"attempt-2","incidentId":"incident-2","tripId":"trip-2","emergencyContactId":"contact-1","status":"Pending","responseType":"None","message":null,"viewedAtUtc":null,"acknowledgedAtUtc":null,"declinedAtUtc":null,"createdAtUtc":"2026-08-15T21:05:53.9166536+00:00","updatedAtUtc":"2026-08-15T21:05:53.9166536+00:00"}
            ],"pageNumber":1,"pageSize":20,"totalCount":9},"error":null}
        """.trimIndent()
    }
}
