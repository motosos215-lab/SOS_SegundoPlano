package com.example.sos_segundoplano.wear

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class WearValidationNotificationActionHandlerTest {
    @Test
    fun confirmSafeNotificationActionDelegatesOnce() = runBlocking {
        val client = FakeValidationActionClient()

        WearValidationNotificationActionHandler(client)
            .handle(WearValidationNotificationActions.CONFIRM_SAFE)

        assertEquals(1, client.confirmSafeCalls)
        assertEquals(0, client.requestHelpCalls)
    }

    @Test
    fun requestHelpNotificationActionDelegatesOnce() = runBlocking {
        val client = FakeValidationActionClient()

        WearValidationNotificationActionHandler(client)
            .handle(WearValidationNotificationActions.REQUEST_HELP)

        assertEquals(0, client.confirmSafeCalls)
        assertEquals(1, client.requestHelpCalls)
    }

    @Test
    fun unknownNotificationActionDoesNothing() = runBlocking {
        val client = FakeValidationActionClient()

        WearValidationNotificationActionHandler(client).handle(null)

        assertEquals(0, client.confirmSafeCalls)
        assertEquals(0, client.requestHelpCalls)
    }

    private class FakeValidationActionClient : WearValidationActionClient {
        var confirmSafeCalls = 0
        var requestHelpCalls = 0

        override suspend fun confirmSafe(): WearValidationActionResult {
            confirmSafeCalls += 1
            return WearValidationActionResult.Sent
        }

        override suspend fun requestHelp(): WearValidationActionResult {
            requestHelpCalls += 1
            return WearValidationActionResult.Sent
        }
    }
}
