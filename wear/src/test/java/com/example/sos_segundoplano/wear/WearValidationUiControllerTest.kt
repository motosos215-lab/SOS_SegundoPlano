package com.example.sos_segundoplano.wear

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WearValidationUiControllerTest {
    @After
    fun resetStore() {
        WearValidationStateStore.resetForTest()
    }

    @Test
    fun confirmSafeSetsInFlightWhileRequestIsRunning() = runTest {
        val gate = CompletableDeferred<Unit>()
        val client = FakeValidationClient(confirmGate = gate)
        val controller = confirmedController(client)

        val job = launch { controller.confirmSafe() }
        runCurrent()

        assertEquals(
            WearValidationUiActionState.InFlight(WearValidationUiOperation.ConfirmSafe),
            controller.actionState.value
        )
        gate.complete(Unit)
        advanceUntilIdle()
        job.join()
    }

    @Test
    fun confirmSafeIgnoresSecondTapWhileInFlight() = runTest {
        val gate = CompletableDeferred<Unit>()
        val client = FakeValidationClient(confirmGate = gate)
        val controller = confirmedController(client)

        val job = launch { controller.confirmSafe() }
        runCurrent()
        controller.confirmSafe()

        assertEquals(1, client.confirmSafeCalls)
        gate.complete(Unit)
        advanceUntilIdle()
        job.join()
    }

    @Test
    fun requestHelpSetsInFlightWhileRequestIsRunning() = runTest {
        val gate = CompletableDeferred<Unit>()
        val client = FakeValidationClient(requestGate = gate)
        val controller = confirmedController(client)

        val job = launch { controller.requestHelp() }
        runCurrent()

        assertEquals(
            WearValidationUiActionState.InFlight(WearValidationUiOperation.RequestHelp),
            controller.actionState.value
        )
        gate.complete(Unit)
        advanceUntilIdle()
        job.join()
    }

    @Test
    fun secondValidationActionIsIgnoredWhileAnotherIsInFlight() = runTest {
        val gate = CompletableDeferred<Unit>()
        val client = FakeValidationClient(confirmGate = gate)
        val controller = confirmedController(client)

        val job = launch { controller.confirmSafe() }
        runCurrent()
        controller.requestHelp()

        assertEquals(1, client.confirmSafeCalls)
        assertEquals(0, client.requestHelpCalls)
        gate.complete(Unit)
        advanceUntilIdle()
        job.join()
    }

    @Test
    fun confirmSafeSentDoesNotChangeConfirmedCountdown() = runTest {
        val status = countdown()
        WearValidationStateStore.confirm(status)
        val controller = WearValidationUiController(FakeValidationClient())

        controller.confirmSafe()

        assertEquals(status, WearValidationStateStore.state.value)
        assertEquals(
            WearValidationUiActionState.Sent(WearValidationUiOperation.ConfirmSafe),
            controller.actionState.value
        )
    }

    @Test
    fun requestHelpSentDoesNotChangeConfirmedCountdown() = runTest {
        val status = countdown()
        WearValidationStateStore.confirm(status)
        val controller = WearValidationUiController(FakeValidationClient())

        controller.requestHelp()

        assertEquals(status, WearValidationStateStore.state.value)
        assertEquals(
            WearValidationUiActionState.Sent(WearValidationUiOperation.RequestHelp),
            controller.actionState.value
        )
    }

    @Test
    fun retryableFailureExposesRetryWithoutRawFailureDetails() = runTest {
        val client = FakeValidationClient(
            confirmResults = ArrayDeque(listOf(WearValidationActionResult.CompanionUnavailable))
        )
        val controller = confirmedController(client)

        controller.confirmSafe()

        val state = controller.actionState.value as WearValidationUiActionState.Error
        assertTrue(state.retryable)
        assertFalse(state.message.contains("Throwable"))
    }

    @Test
    fun retryRepeatsSameLogicalValidationAction() = runTest {
        val client = FakeValidationClient(
            confirmResults = ArrayDeque(
                listOf(WearValidationActionResult.TransportFailure, WearValidationActionResult.Sent)
            )
        )
        val controller = confirmedController(client)

        controller.confirmSafe()
        controller.retry()

        assertEquals(2, client.confirmSafeCalls)
        assertEquals(0, client.requestHelpCalls)
    }

    @Test
    fun retryRequestHelpRepeatsRequestHelp() = runTest {
        val client = FakeValidationClient(
            requestResults = ArrayDeque(
                listOf(WearValidationActionResult.Timeout, WearValidationActionResult.Sent)
            )
        )
        val controller = confirmedController(client)

        controller.requestHelp()
        controller.retry()

        assertEquals(0, client.confirmSafeCalls)
        assertEquals(2, client.requestHelpCalls)
    }

    @Test
    fun confirmSafeWithoutActiveCountdownDoesNotStartAction() = runTest {
        WearValidationStateStore.resetForTest()
        val client = FakeValidationClient()
        val controller = WearValidationUiController(client)

        controller.confirmSafe()

        assertEquals(0, client.confirmSafeCalls)
        assertEquals(0, client.requestHelpCalls)
        assertEquals(WearValidationUiActionState.Idle, controller.actionState.value)
    }

    @Test
    fun requestHelpWithoutActiveCountdownDoesNotStartAction() = runTest {
        WearValidationStateStore.resetForTest()
        val client = FakeValidationClient()
        val controller = WearValidationUiController(client)

        controller.requestHelp()

        assertEquals(0, client.confirmSafeCalls)
        assertEquals(0, client.requestHelpCalls)
        assertEquals(WearValidationUiActionState.Idle, controller.actionState.value)
    }

    @Test
    fun newValidationAssessmentClearsPreviousActionFeedback() = runTest {
        val client = FakeValidationClient()
        val controller = confirmedController(client)

        controller.confirmSafe()
        WearValidationStateStore.confirm(countdown(assessmentId = 303L))
        controller.onValidationStatusChanged(WearValidationStateStore.state.value)

        assertEquals(WearValidationUiActionState.Idle, controller.actionState.value)
    }

    @Test
    fun sameAssessmentRemainingUpdateKeepsActionFeedback() = runTest {
        val client = FakeValidationClient()
        val controller = confirmedController(client)

        controller.confirmSafe()
        WearValidationStateStore.confirm(countdown(remainingMillis = 19_000L))
        controller.onValidationStatusChanged(WearValidationStateStore.state.value)

        assertEquals(
            WearValidationUiActionState.Sent(WearValidationUiOperation.ConfirmSafe),
            controller.actionState.value
        )
    }

    @Test
    fun phoneLeavingCountdownClearsValidationActionState() = runTest {
        val controller = confirmedController(FakeValidationClient())

        controller.confirmSafe()
        WearValidationStateStore.confirm(WearDataLayerProtocol.ValidationStatus(state = "safe_confirmed"))
        controller.onValidationStatusChanged(WearValidationStateStore.state.value)

        assertEquals(WearValidationUiActionState.Idle, controller.actionState.value)
    }

    private fun confirmedController(client: FakeValidationClient): WearValidationUiController {
        WearValidationStateStore.confirm(countdown())
        return WearValidationUiController(client)
    }

    private fun countdown(
        assessmentId: Long = 202L,
        remainingMillis: Long = 20_000L
    ) = WearDataLayerProtocol.ValidationStatus(
        state = "countdown_active",
        sessionId = 101L,
        assessmentId = assessmentId,
        remainingMillis = remainingMillis
    )

    private class FakeValidationClient(
        private val confirmResults: ArrayDeque<WearValidationActionResult> =
            ArrayDeque(listOf(WearValidationActionResult.Sent)),
        private val requestResults: ArrayDeque<WearValidationActionResult> =
            ArrayDeque(listOf(WearValidationActionResult.Sent)),
        private val confirmGate: CompletableDeferred<Unit>? = null,
        private val requestGate: CompletableDeferred<Unit>? = null
    ) : WearValidationActionClient {
        var confirmSafeCalls = 0
            private set
        var requestHelpCalls = 0
            private set

        override suspend fun confirmSafe(): WearValidationActionResult {
            confirmSafeCalls += 1
            confirmGate?.await()
            return next(confirmResults)
        }

        override suspend fun requestHelp(): WearValidationActionResult {
            requestHelpCalls += 1
            requestGate?.await()
            return next(requestResults)
        }

        private fun next(results: ArrayDeque<WearValidationActionResult>): WearValidationActionResult =
            if (results.isEmpty()) WearValidationActionResult.Sent else results.removeFirst()
    }
}
