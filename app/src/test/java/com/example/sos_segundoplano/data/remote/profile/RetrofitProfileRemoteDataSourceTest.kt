package com.example.sos_segundoplano.data.remote.profile

import com.example.sos_segundoplano.data.remote.auth.AuthNetworkFactory
import com.example.sos_segundoplano.domain.profile.ProfileAccountUnavailable
import com.example.sos_segundoplano.domain.profile.ProfileInvalidResponse
import com.example.sos_segundoplano.domain.profile.ProfileRateLimited
import com.example.sos_segundoplano.domain.profile.ProfileResult
import com.example.sos_segundoplano.domain.profile.ProfileServerFailure
import com.example.sos_segundoplano.domain.profile.ProfileUnauthorized
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RetrofitProfileRemoteDataSourceTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun usesGetExactPathBearerHeaderAndNoBody() = runBlocking {
        server.enqueue(jsonResponse(200, VALID_PROFILE))

        val result = source().me("Bearer token-de-prueba")

        assertTrue(result is ProfileResult.Success)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/v1/users/me", request.path)
        assertEquals("Bearer token-de-prueba", request.getHeader("Authorization"))
        assertEquals(0L, request.bodySize)
    }

    @Test fun mapsHandledResponsesWithoutBackendMessage() = runBlocking {
        val cases = listOf(
            jsonResponse(200, """{"success":false,"data":null,"error":{"code":"validation_error","message":"raw"}}""") to ProfileInvalidResponse::class,
            jsonResponse(401, """{"success":false,"data":null,"error":{"code":"unauthorized","message":"raw"}}""") to ProfileUnauthorized::class,
            MockResponse().setResponseCode(401) to ProfileUnauthorized::class,
            jsonResponse(404, """{"success":false,"data":null,"error":{"code":"not_found","message":"raw"}}""") to ProfileAccountUnavailable::class,
            jsonResponse(429, """{"success":false,"data":null,"error":{"code":"rate_limited","message":"raw"}}""") to ProfileRateLimited::class,
            MockResponse().setResponseCode(500) to ProfileServerFailure::class,
            jsonResponse(200, "{") to ProfileInvalidResponse::class,
            jsonResponse(200, """{"success":true,"data":null,"error":null}""") to ProfileInvalidResponse::class,
            jsonResponse(200, """{"success":true,"data":{"user":null},"error":null}""") to ProfileInvalidResponse::class
        )
        cases.forEach { (response, expectedClass) ->
            server.enqueue(response)
            assertEquals(expectedClass, source().me("Bearer token-de-prueba")::class)
        }
    }

    @Test fun successPayloadAllowsDomainValidationCasesToReachRepository() = runBlocking {
        val payloads = listOf(
            VALID_PROFILE.replace("\"Rider\"", "\"Driver\""),
            VALID_PROFILE.replace("\"isActive\": true", "\"isActive\": false"),
            VALID_PROFILE.replace("2026-08-04T12:00:00+00:00", "invalid-date"),
            VALID_PROFILE.replace("\"id\": \"rider-id\"", "\"id\": \"\"")
        )
        payloads.forEach { payload ->
            server.enqueue(jsonResponse(200, payload))
            assertTrue(source().me("Bearer token-de-prueba") is ProfileResult.Success)
        }
    }

    private fun source(): RetrofitProfileRemoteDataSource {
        val moshi = AuthNetworkFactory.createMoshi()
        val api = AuthNetworkFactory.createUsersApi(server.url("/").toString(), moshi)
        return RetrofitProfileRemoteDataSource(api, moshi)
    }

    private fun jsonResponse(code: Int, body: String): MockResponse = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private companion object {
        val VALID_PROFILE = """
            {
              "success": true,
              "data": {
                "user": {
                  "id": "rider-id",
                  "email": "rider@example.com",
                  "fullName": "Moto Rider",
                  "phoneNumber": "+52 555 555 5555",
                  "role": "Rider",
                  "isActive": true,
                  "createdAtUtc": "2026-08-04T12:00:00+00:00",
                  "updatedAtUtc": "2026-08-04T12:05:00+00:00",
                  "lastLoginAtUtc": "2026-08-04T12:05:00+00:00"
                }
              },
              "error": null
            }
        """.trimIndent()
    }
}
