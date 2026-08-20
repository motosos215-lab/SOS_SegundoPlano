package com.example.sos_segundoplano.wear

import com.example.sos_segundoplano.wearprotocol.PhoneActionResponse
import com.example.sos_segundoplano.wearprotocol.PhoneActionResult
import com.example.sos_segundoplano.wearprotocol.TripStateResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WearTripUiControllerTest {
    @Test
    fun refreshInitialDelegatesOnce() = runTest {
        val actions = FakeActions()
        val controller = controller(actions)

        controller.refreshInitial()
        controller.refreshInitial()

        assertEquals(1, actions.refreshCalls)
    }

    @Test
    fun startTripGeneratesCommandIdForNewAction() = runTest {
        val actions = FakeActions()
        val store = confirmedInactiveStore()
        val controller = controller(actions, store, listOf("command-start-ui-001"))

        controller.startTrip()

        assertEquals(listOf("command-start-ui-001"), actions.startCommands)
        assertFalse(store.state.value.active!!)
    }

    @Test
    fun startTripSetsInFlightWhileActionIsRunning() = runTest {
        val gate = CompletableDeferred<Unit>()
        val actions = FakeActions(startGate = gate)
        val controller = controller(actions)

        val job = launch { controller.startTrip() }
        runCurrent()

        assertEquals(WearTripUiActionState.InFlight(WearTripUiOperation.Start), controller.actionState.value)
        gate.complete(Unit)
        advanceUntilIdle()
        job.join()
    }

    @Test
    fun startTripIgnoresSecondTapWhileInFlight() = runTest {
        val gate = CompletableDeferred<Unit>()
        val actions = FakeActions(startGate = gate)
        val controller = controller(actions, commandIds = listOf("command-start-ui-001", "command-start-ui-002"))

        val job = launch { controller.startTrip() }
        runCurrent()
        controller.startTrip()

        assertEquals(listOf("command-start-ui-001"), actions.startCommands)
        gate.complete(Unit)
        advanceUntilIdle()
        job.join()
    }

    @Test
    fun startTripSuccessDoesNotInventActiveTrip() = runTest {
        val store = confirmedInactiveStore()
        val controller = controller(FakeActions(), store)

        controller.startTrip()

        assertFalse(store.state.value.active!!)
        assertEquals(null, store.state.value.remoteTripId)
    }

    @Test
    fun retryStartReusesSameCommandId() = runTest {
        val actions = FakeActions(startResults = ArrayDeque(listOf(retryableResponse(), okResponse())))
        val controller = controller(actions, commandIds = listOf("command-start-ui-001", "command-start-ui-002"))

        controller.startTrip()
        controller.retry()

        assertEquals(listOf("command-start-ui-001", "command-start-ui-001"), actions.startCommands)
    }

    @Test
    fun newStartAfterTerminalResultUsesNewCommandId() = runTest {
        val actions = FakeActions(startResults = ArrayDeque(listOf(notAuthenticatedResponse(), okResponse())))
        val controller = controller(actions, commandIds = listOf("command-start-ui-001", "command-start-ui-002"))

        controller.startTrip()
        controller.startTrip()

        assertEquals(listOf("command-start-ui-001", "command-start-ui-002"), actions.startCommands)
    }

    @Test
    fun finishTripWithoutConfirmedRemoteTripIdDoesNotSend() = runTest {
        val store = confirmedActiveStore(remoteTripId = null)
        val actions = FakeActions()
        val controller = controller(actions, store)

        controller.finishTrip()

        assertEquals(emptyList<FinishCall>(), actions.finishCalls)
        assertTrue(controller.actionState.value is WearTripUiActionState.Error)
    }

    @Test
    fun finishTripUsesConfirmedRemoteTripId() = runTest {
        val store = confirmedActiveStore("trip-ui-123")
        val actions = FakeActions()
        val controller = controller(actions, store, listOf("command-finish-ui-001"))

        controller.finishTrip()

        assertEquals(listOf(FinishCall("command-finish-ui-001", "trip-ui-123")), actions.finishCalls)
    }

    @Test
    fun finishTripIgnoresSecondTapWhileInFlight() = runTest {
        val gate = CompletableDeferred<Unit>()
        val store = confirmedActiveStore("trip-ui-123")
        val actions = FakeActions(finishGate = gate)
        val controller = controller(actions, store)

        val job = launch { controller.finishTrip() }
        runCurrent()
        controller.finishTrip()

        assertEquals(1, actions.finishCalls.size)
        gate.complete(Unit)
        advanceUntilIdle()
        job.join()
    }

    @Test
    fun finishTripSuccessDoesNotInventInactiveTrip() = runTest {
        val store = confirmedActiveStore("trip-ui-123")
        val controller = controller(FakeActions(), store)

        controller.finishTrip()

        assertTrue(store.state.value.active!!)
        assertEquals("trip-ui-123", store.state.value.remoteTripId)
    }

    @Test
    fun retryFinishReusesSameCommandId() = runTest {
        val store = confirmedActiveStore("trip-ui-123")
        val actions = FakeActions(finishResults = ArrayDeque(listOf(retryableResponse(), okResponse())))
        val controller = controller(actions, store, listOf("command-finish-ui-001", "command-finish-ui-002"))

        controller.finishTrip()
        controller.retry()

        assertEquals(
            listOf(
                FinishCall("command-finish-ui-001", "trip-ui-123"),
                FinishCall("command-finish-ui-001", "trip-ui-123")
            ),
            actions.finishCalls
        )
    }

    @Test
    fun noActiveTripResponseTriggersRefresh() = runTest {
        val actions = FakeActions(finishResults = ArrayDeque(listOf(response(PhoneActionResult.NO_ACTIVE_TRIP))))
        val controller = controller(actions, confirmedActiveStore("trip-ui-123"))

        controller.finishTrip()

        assertEquals(1, actions.refreshCalls)
    }

    @Test
    fun tripMismatchResponseTriggersRefresh() = runTest {
        val actions = FakeActions(finishResults = ArrayDeque(listOf(response(PhoneActionResult.TRIP_MISMATCH))))
        val controller = controller(actions, confirmedActiveStore("trip-ui-123"))

        controller.finishTrip()

        assertEquals(1, actions.refreshCalls)
    }

    @Test
    fun retryableFailureExposesRetryWithoutRawException() = runTest {
        val actions = FakeActions(startResults = ArrayDeque(listOf(response(PhoneActionResult.RETRYABLE_ERROR, "internal-code"))))
        val controller = controller(actions)

        controller.startTrip()

        val state = controller.actionState.value as WearTripUiActionState.Error
        assertTrue(state.retryable)
        assertFalse(state.message.contains("internal-code"))
    }

    @Test
    fun notAuthenticatedIsTerminalAndDoesNotReusePendingAction() = runTest {
        val actions = FakeActions(startResults = ArrayDeque(listOf(notAuthenticatedResponse(), okResponse())))
        val controller = controller(actions, commandIds = listOf("command-start-ui-001", "command-start-ui-002"))

        controller.startTrip()
        val state = controller.actionState.value as WearTripUiActionState.Error
        controller.startTrip()

        assertFalse(state.retryable)
        assertEquals(listOf("command-start-ui-001", "command-start-ui-002"), actions.startCommands)
    }

    @Test
    fun companionUnavailableIsRetryable() = runTest {
        val actions = FakeActions(startResults = ArrayDeque(listOf(MobileCompanionResult.CompanionUnavailable)))
        val controller = controller(actions)

        controller.startTrip()

        assertTrue((controller.actionState.value as WearTripUiActionState.Error).retryable)
    }

    private fun controller(
        actions: FakeActions,
        store: WearTripStateStore = confirmedInactiveStore(),
        commandIds: List<String> = listOf("command-ui-default")
    ): WearTripUiController {
        val ids = ArrayDeque(commandIds)
        return WearTripUiController(actions, store) { ids.removeFirst() }
    }

    private fun confirmedInactiveStore() = WearTripStateStore().also {
        it.confirm(TripStateResponse("state", false, null, null, 1L))
    }

    private fun confirmedActiveStore(remoteTripId: String?) = WearTripStateStore().also {
        it.confirm(TripStateResponse("state", true, remoteTripId, 10L, 1L))
    }

    private fun okResponse() = response(PhoneActionResult.OK)
    private fun notAuthenticatedResponse() = response(PhoneActionResult.NOT_AUTHENTICATED)
    private fun retryableResponse() = response(PhoneActionResult.RETRYABLE_ERROR)
    private fun response(result: PhoneActionResult, code: String? = null) =
        MobileCompanionResult.Success(PhoneActionResponse("request", result, code, respondedAtEpochMs = 1L))

    private data class FinishCall(val commandId: String, val remoteTripId: String)

    private class FakeActions(
        private val startResults: ArrayDeque<MobileCompanionResult<PhoneActionResponse>> =
            ArrayDeque(listOf(WearTripUiControllerTest.defaultOkResponse())),
        private val finishResults: ArrayDeque<MobileCompanionResult<PhoneActionResponse>> =
            ArrayDeque(listOf(WearTripUiControllerTest.defaultOkResponse())),
        private val startGate: CompletableDeferred<Unit>? = null,
        private val finishGate: CompletableDeferred<Unit>? = null
    ) : WearTripUiActions {
        var refreshCalls = 0
        val startCommands = mutableListOf<String>()
        val finishCalls = mutableListOf<FinishCall>()

        override suspend fun refreshTripState(): MobileCompanionResult<TripStateResponse> {
            refreshCalls += 1
            return MobileCompanionResult.Success(TripStateResponse("refresh", false, null, null, 1L))
        }

        override suspend fun startTrip(commandId: String): MobileCompanionResult<PhoneActionResponse> {
            startCommands += commandId
            startGate?.await()
            return startResults.removeFirst()
        }

        override suspend fun finishTrip(
            commandId: String,
            remoteTripId: String
        ): MobileCompanionResult<PhoneActionResponse> {
            finishCalls += FinishCall(commandId, remoteTripId)
            finishGate?.await()
            return finishResults.removeFirst()
        }
    }

    private companion object {
        fun defaultOkResponse(): MobileCompanionResult<PhoneActionResponse> =
            MobileCompanionResult.Success(
                PhoneActionResponse("request", PhoneActionResult.OK, respondedAtEpochMs = 1L)
            )
    }
}
