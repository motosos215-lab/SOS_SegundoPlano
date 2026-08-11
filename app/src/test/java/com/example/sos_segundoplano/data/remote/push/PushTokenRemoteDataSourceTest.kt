package com.example.sos_segundoplano.data.remote.push

import com.example.sos_segundoplano.data.remote.auth.AuthNetworkFactory
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PushTokenRemoteDataSourceTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun unauthorizedWithEmptyBodyDoesNotCrash() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = dataSource().register("Bearer expired", request())

        assertEquals(PushTokenRemoteResult.HttpFailure(401), result)
    }

    @Test fun notFoundPreservesConfirmedErrorCode() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(404).setBody(
                """{"success":false,"data":null,"error":{"code":"push_notification_token_not_available","message":"Not available"}}"""
            )
        )

        val result = dataSource().revoke("Bearer access", "missing-id")

        assertEquals(
            PushTokenRemoteResult.NotFound("push_notification_token_not_available"),
            result
        )
    }

    @Test fun successFalseIsHandledWithoutUsingData() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":false,"data":null,"error":{"code":"validation_error","message":"Rejected"}}"""
            )
        )

        val result = dataSource().register("Bearer access", request())

        assertEquals(PushTokenRemoteResult.HttpFailure(200, "validation_error"), result)
    }

    @Test fun listAndStatusAreTypedButStatusCannotSupplyRegistrationId() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"data":{"pushNotificationTokens":[],"pageNumber":1,"pageSize":20,"totalCount":0},"error":null}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"data":{"activeTokenCount":0,"revokedTokenCount":0,"hasActiveAndroidFcm":false,"hasActiveIosApns":false,"hasActiveWebPush":false,"hasActiveWebFcm":false,"lastRegisteredAtUtc":null},"error":null}"""
            )
        )
        val source = dataSource()

        val list = source.listActiveAndroidFcm("Bearer access")
        val status = source.status("Bearer access")

        assertTrue((list as PushTokenRemoteResult.Listed).data.pushNotificationTokens.isEmpty())
        assertEquals(0, (status as PushTokenRemoteResult.StatusLoaded).data.activeTokenCount)
    }

    private fun dataSource(): PushTokenRemoteDataSource {
        val moshi = AuthNetworkFactory.createMoshi()
        val api = AuthNetworkFactory.createPushNotificationTokensApi(server.url("/").toString(), moshi)
        return RetrofitPushTokenRemoteDataSource(api, moshi)
    }

    private fun request() = RegisterPushTokenRequestDto(
        platform = "Android",
        channel = "Fcm",
        token = "fake-token-not-real",
        metadata = PushTokenMetadataDto("1.0-test", "Android-test")
    )
}
