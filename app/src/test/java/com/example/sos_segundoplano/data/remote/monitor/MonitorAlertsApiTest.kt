package com.example.sos_segundoplano.data.remote.monitor

import com.example.sos_segundoplano.data.remote.auth.AuthNetworkFactory
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MonitorAlertsApiTest {
    private lateinit var server: MockWebServer
    @Before fun setup() { server = MockWebServer(); server.start() }
    @After fun teardown() { server.shutdown() }

    @Test fun usesNotificationDeliveryAttemptIdForEveryRoute() = runBlocking {
        server.enqueue(json("""{"success":true,"data":[],"error":null}"""))
        repeat(6) { server.enqueue(json("""{"success":true,"data":{},"error":null}""")) }
        val api = api()
        api.list("Bearer monitor")
        api.detail("Bearer monitor", "attempt-1")
        api.status("Bearer monitor", "attempt-1")
        api.location("Bearer monitor", "attempt-1")
        api.view("Bearer monitor", "attempt-1")
        api.acknowledge("Bearer monitor", "attempt-1", AcknowledgeMonitorAlertRequestDto("CanAssist", "Available"))
        api.decline("Bearer monitor", "attempt-1", DeclineMonitorAlertRequestDto("Unavailable"))
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
        assertTrue(decline.body.readUtf8().contains("\"reason\":\"Unavailable\""))
    }

    private fun api() = AuthNetworkFactory.createMonitorAlertsApi(server.url("/").toString())
    private fun json(body: String) = MockResponse().setResponseCode(200).setBody(body)
}
