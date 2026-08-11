package com.example.sos_segundoplano.data.remote.push

import com.example.sos_segundoplano.data.remote.auth.AuthNetworkFactory
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PushNotificationTokensApiTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun registerUsesExactMonitorRequestAndParsesNestedRegistrationId() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(registerResponse("registration-id")))
        val api = api()

        val response = api.register(
            "Bearer monitor-access-token",
            RegisterPushTokenRequestDto(
                platform = "Android",
                channel = "Fcm",
                token = FAKE_TOKEN,
                metadata = PushTokenMetadataDto("1.0-test", "Android-test")
            )
        )

        val request = server.takeRequest()
        val json = request.body.readUtf8()
        assertEquals("/api/v1/push-notification-tokens", request.path)
        assertEquals("Bearer monitor-access-token", request.getHeader("Authorization"))
        assertTrue(json.contains("\"platform\":\"Android\""))
        assertTrue(json.contains("\"channel\":\"Fcm\""))
        assertTrue(json.contains("\"appVersion\":\"1.0-test\""))
        assertTrue(json.contains("\"osVersion\":\"Android-test\""))
        assertFalse(json.contains("userId"))
        assertFalse(json.contains("deviceId"))
        assertEquals("registration-id", response.body()?.data?.pushNotificationToken?.id)
        assertNull(response.body()?.data?.pushNotificationToken?.deviceId)
    }

    @Test fun listUsesConfirmedFiltersAndParsesWrappedArrayIncludingEmpty() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(listResponse(includeToken = true)))
        server.enqueue(MockResponse().setResponseCode(200).setBody(listResponse(includeToken = false)))
        val api = api()

        val populated = api.list("Bearer access").body()?.data
        val firstRequest = server.takeRequest()
        val empty = api.list("Bearer access").body()?.data
        server.takeRequest()

        assertEquals(
            "/api/v1/push-notification-tokens?platform=Android&channel=Fcm&status=Active&pageNumber=1&pageSize=20",
            firstRequest.path
        )
        assertEquals("registration-id", populated?.pushNotificationTokens?.single()?.id)
        assertTrue(empty?.pushNotificationTokens?.isEmpty() == true)
        assertEquals(0, empty?.totalCount)
    }

    @Test fun statusParsesConfirmedFieldsAndContainsNoRegistrationId() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(statusResponse()))

        val status = api().status("Bearer access").body()?.data

        assertEquals(1, status?.activeTokenCount)
        assertEquals(0, status?.revokedTokenCount)
        assertTrue(status?.hasActiveAndroidFcm == true)
        assertFalse(PushTokenStatusDataDto::class.java.declaredFields.any { it.name == "id" })
    }

    @Test fun revokeUsesEmptyBodyAndParsesDirectDataId() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(revokeResponse()))

        val response = api().revoke("Bearer monitor-access-token", "registration-id")

        val request = server.takeRequest()
        assertEquals("/api/v1/push-notification-tokens/registration-id/revoke", request.path)
        assertEquals("Bearer monitor-access-token", request.getHeader("Authorization"))
        assertEquals(0L, request.bodySize)
        assertEquals("registration-id", response.body()?.data?.id)
        assertEquals("Revoked", response.body()?.data?.status)
    }

    private fun api() = AuthNetworkFactory.createPushNotificationTokensApi(server.url("/").toString())

    private fun registerResponse(id: String) = """
        {"success":true,"data":{"pushNotificationToken":${tokenJson(id, "Active", null)}},"error":null}
    """.trimIndent()

    private fun listResponse(includeToken: Boolean) = """
        {"success":true,"data":{"pushNotificationTokens":[${if (includeToken) tokenJson("registration-id", "Active", null) else ""}],"pageNumber":1,"pageSize":20,"totalCount":${if (includeToken) 1 else 0}},"error":null}
    """.trimIndent()

    private fun statusResponse() = """
        {"success":true,"data":{"activeTokenCount":1,"revokedTokenCount":0,"hasActiveAndroidFcm":true,"hasActiveIosApns":false,"hasActiveWebPush":false,"hasActiveWebFcm":false,"lastRegisteredAtUtc":"2026-08-11T00:00:00Z"},"error":null}
    """.trimIndent()

    private fun revokeResponse() = """
        {"success":true,"data":${tokenJson("registration-id", "Revoked", "2026-08-11T01:00:00Z")},"error":null}
    """.trimIndent()

    private fun tokenJson(id: String, status: String, revokedAt: String?) = """
        {"id":"$id","platform":"Android","channel":"Fcm","deviceId":null,"tokenPreview":"abcdef****wxyz","status":"$status","registeredAtUtc":"2026-08-11T00:00:00Z","lastSeenAtUtc":"2026-08-11T00:30:00Z","revokedAtUtc":${revokedAt?.let { "\"$it\"" } ?: "null"}}
    """.trimIndent()

    private companion object {
        const val FAKE_TOKEN = "fake-fcm-token-not-real"
    }
}
