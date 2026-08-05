package com.example.sos_segundoplano.data.remote.auth

import com.example.sos_segundoplano.domain.auth.AuthResult
import com.example.sos_segundoplano.domain.auth.InvalidCredentials
import com.example.sos_segundoplano.domain.auth.InvalidResponse
import com.example.sos_segundoplano.domain.auth.NetworkUnavailable
import com.example.sos_segundoplano.domain.auth.RateLimited
import com.example.sos_segundoplano.domain.auth.ServerFailure
import com.example.sos_segundoplano.domain.auth.Timeout
import com.example.sos_segundoplano.domain.auth.Unauthorized
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import retrofit2.http.POST
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class RetrofitAuthRemoteDataSourceTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun loginParsesEnvelopeAndUsesExactPathWithoutDuplicatedSlash() = runBlocking {
        server.enqueue(jsonResponse(200, LOGIN_SUCCESS))
        val result = source().login(LoginRequestDto("rider@example.com", "StrongPass1!", false))

        assertTrue(result is AuthResult.Success)
        val data = (result as AuthResult.Success).value
        assertEquals("Rider", data.user.role)
        val request = server.takeRequest()
        assertEquals("/api/v1/auth/login", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"password\":\"StrongPass1!\""))
        assertTrue(body.contains("\"rememberMe\":false"))
    }

    @Test fun handled200FailureAnd401WrapperMapInvalidCredentials() = runBlocking {
        server.enqueue(jsonResponse(200, INVALID_CREDENTIALS))
        assertTrue(source().login(loginRequest()) is InvalidCredentials)

        server.enqueue(jsonResponse(401, INVALID_CREDENTIALS))
        assertTrue(source().login(loginRequest()) is InvalidCredentials)
    }

    @Test fun missingDataEmptyBodyAndMalformedJsonAreInvalidResponses() = runBlocking {
        server.enqueue(jsonResponse(200, """{"success":true,"data":null,"error":null}"""))
        assertTrue(source().login(loginRequest()) is InvalidResponse)

        server.enqueue(MockResponse().setResponseCode(200))
        assertTrue(source().login(loginRequest()) is InvalidResponse)

        server.enqueue(jsonResponse(200, "{"))
        assertTrue(source().login(loginRequest()) is InvalidResponse)
    }

    @Test fun unauthorizedWithoutWrapperDoesNotBreakParser() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        assertTrue(source().login(loginRequest()) is Unauthorized)
    }

    @Test fun rateLimitPreservesRetryAfter() = runBlocking {
        server.enqueue(
            jsonResponse(429, """{"success":false,"data":null,"error":{"code":"rate_limited","message":"wait"}}""")
                .setHeader("Retry-After", "45")
        )
        val result = source().login(loginRequest())
        assertTrue(result is RateLimited)
        assertEquals("45", (result as RateLimited).retryAfter)
    }

    @Test fun serverFailuresWithAndWithoutWrapperAreTyped() = runBlocking {
        server.enqueue(jsonResponse(500, """{"success":false,"data":null,"error":{"code":"server_error","message":"failed"}}"""))
        assertTrue(source().login(loginRequest()) is ServerFailure)

        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(source().login(loginRequest()) is ServerFailure)
    }

    @Test fun refreshParsesRotatedTokens() = runBlocking {
        server.enqueue(jsonResponse(200, REFRESH_SUCCESS))
        val result = source().refresh(RefreshTokenRequestDto("old-refresh"))
        assertTrue(result is AuthResult.Success)
        assertEquals("new-refresh", (result as AuthResult.Success).value.refreshToken)
        assertEquals("/api/v1/auth/refresh", server.takeRequest().path)
    }

    @Test fun logoutAccepts204WithoutReadingBody() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        assertTrue(source().logout(LogoutRequestDto("refresh-token")) is AuthResult.Success)
        assertEquals("/api/v1/auth/logout", server.takeRequest().path)
    }

    @Test fun timeoutAndUnavailableNetworkAreTyped() = runBlocking {
        server.enqueue(jsonResponse(200, LOGIN_SUCCESS).setBodyDelay(1, TimeUnit.SECONDS))
        val shortClient = OkHttpClient.Builder()
            .connectTimeout(100, TimeUnit.MILLISECONDS)
            .readTimeout(100, TimeUnit.MILLISECONDS)
            .writeTimeout(100, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
        assertTrue(source(shortClient).login(loginRequest()) is Timeout)

        val unavailable = RetrofitAuthRemoteDataSource(ThrowingAuthApi(), AuthNetworkFactory.createMoshi())
        assertTrue(unavailable.login(loginRequest()) is NetworkUnavailable)
    }

    @Test fun authApiContainsOnlyAuthorizedMethodsAndRoutes() {
        val methods = AuthApi::class.java.declaredMethods
        assertEquals(setOf("login", "refresh", "logout"), methods.map { it.name }.toSet())
        val routes = methods.mapNotNull { it.getAnnotation(POST::class.java)?.value }.toSet()
        assertEquals(
            setOf("api/v1/auth/login", "api/v1/auth/refresh", "api/v1/auth/logout"),
            routes
        )
        val allRoutes = routes.joinToString("|")
        assertFalse(allRoutes.contains("register"))
        assertFalse(allRoutes.contains("forgot-password"))
        assertFalse(allRoutes.contains("access-code"))
        assertFalse(allRoutes.contains("users/me"))
    }

    @Test fun secretDtosRedactPasswordAndTokensFromToString() {
        val values = listOf(
            LoginRequestDto("rider@example.com", "plain-password", true).toString(),
            RefreshTokenRequestDto("plain-refresh").toString(),
            LogoutRequestDto("plain-refresh").toString(),
            RefreshDataDto("plain-access", "plain-refresh", "2026-08-04T12:00:00Z").toString(),
            LoginDataDto(
                "plain-access",
                "plain-refresh",
                "2026-08-04T12:00:00Z",
                AuthUserDto("id", "rider@example.com", "Rider", "phone", "Rider", true)
            ).toString()
        ).joinToString("|")
        assertFalse(values.contains("plain-password"))
        assertFalse(values.contains("plain-access"))
        assertFalse(values.contains("plain-refresh"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun baseUrlMustEndWithSlash() {
        AuthNetworkFactory.createApi(
            server.url("/").toString().removeSuffix("/"),
            AuthNetworkFactory.createMoshi()
        )
    }

    private fun source(client: OkHttpClient? = null): RetrofitAuthRemoteDataSource {
        val moshi = AuthNetworkFactory.createMoshi()
        val api = if (client == null) {
            AuthNetworkFactory.createApi(server.url("/").toString(), moshi)
        } else {
            AuthNetworkFactory.createApi(server.url("/").toString(), moshi, client)
        }
        return RetrofitAuthRemoteDataSource(api, moshi)
    }

    private fun loginRequest() = LoginRequestDto("rider@example.com", "password", false)

    private fun jsonResponse(code: Int, body: String): MockResponse = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private companion object {
        val LOGIN_SUCCESS = """
            {
              "success": true,
              "data": {
                "accessToken": "access-token",
                "refreshToken": "refresh-token",
                "accessTokenExpiresAtUtc": "2026-08-04T12:00:00+00:00",
                "user": {
                  "id": "rider-id",
                  "email": "rider@example.com",
                  "fullName": "Moto Rider",
                  "phoneNumber": "+52 555 555 5555",
                  "role": "Rider",
                  "isActive": true
                }
              },
              "error": null
            }
        """.trimIndent()

        val INVALID_CREDENTIALS = """
            {"success":false,"data":null,"error":{"code":"invalid_credentials","message":"Invalid authentication credentials."}}
        """.trimIndent()

        val REFRESH_SUCCESS = """
            {
              "success": true,
              "data": {
                "accessToken": "new-access",
                "refreshToken": "new-refresh",
                "accessTokenExpiresAtUtc": "2026-08-04T12:00:00Z"
              },
              "error": null
            }
        """.trimIndent()
    }
}

private class ThrowingAuthApi : AuthApi {
    override suspend fun login(request: LoginRequestDto): Response<ApiEnvelopeDto<LoginDataDto>> =
        throw UnknownHostException("unavailable")

    override suspend fun refresh(request: RefreshTokenRequestDto): Response<ApiEnvelopeDto<RefreshDataDto>> =
        throw UnknownHostException("unavailable")

    override suspend fun logout(request: LogoutRequestDto): Response<Unit> =
        throw UnknownHostException("unavailable")
}
