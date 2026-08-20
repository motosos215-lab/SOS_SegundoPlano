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
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WearManualSosUiControllerTest {
    @Test
    fun completingHoldSendsManualSosOnceWithoutChangingTripState() = runTest {
        val actions = FakeActions()
        val store = activeStore()
        val controller = controller(actions, store)

        controller.startHold()
        controller.updateHoldProgress(1f)
        controller.confirmHeld()

        assertEquals(listOf(ManualSosCall("manual-sos-1", "trip-1")), actions.manualSosCalls)
        assertTrue(store.state.value.active!!)
        assertEquals("trip-1", store.state.value.remoteTripId)
        assertEquals(WearManualSosUiState.Success, controller.state.value)
    }

    @Test
    fun cancellingHoldBeforeThresholdSendsNothing() = runTest {
        val actions = FakeActions()
        val controller = controller(actions, activeStore())

        controller.startHold()
        controller.updateHoldProgress(.5f)
        controller.cancelHold()
        controller.confirmHeld()

        assertEquals(emptyList<ManualSosCall>(), actions.manualSosCalls)
        assertEquals(WearManualSosUiState.Idle, controller.state.value)
    }

    @Test
    fun secondConfirmationWhileInFlightIsIgnored() = runTest {
        val gate = CompletableDeferred<Unit>()
        val actions = FakeActions(gate = gate)
        val controller = controller(actions, activeStore())

        controller.startHold()
        val job = launch { controller.confirmHeld() }
        runCurrent()
        controller.confirmHeld()

        assertEquals(1, actions.manualSosCalls.size)
        assertEquals(WearManualSosUiState.InFlight, controller.state.value)
        gate.complete(Unit)
        advanceUntilIdle()
        job.join()
    }

    @Test
    fun retryUsesTheSameCommandIdAfterRecoverableFailure() = runTest {
        val actions = FakeActions(
            results = ArrayDeque(listOf(response(PhoneActionResult.RETRYABLE_ERROR), response(PhoneActionResult.OK)))
        )
        val controller = controller(actions, activeStore())

        controller.startHold()
        controller.confirmHeld()
        controller.retry()

        assertEquals(
            listOf(ManualSosCall("manual-sos-1", "trip-1"), ManualSosCall("manual-sos-1", "trip-1")),
            actions.manualSosCalls
        )
        assertEquals(WearManualSosUiState.Success, controller.state.value)
    }

    @Test
    fun successDoesNotUseFinishOrValidationActionsAndNewSosGetsNewCommandId() = runTest {
        val actions = FakeActions()
        val controller = controller(actions, activeStore(), listOf("manual-sos-1", "manual-sos-2"))

        controller.startHold()
        controller.confirmHeld()
        controller.beginNewManualSos()
        controller.startHold()
        controller.confirmHeld()

        assertEquals(
            listOf(ManualSosCall("manual-sos-1", "trip-1"), ManualSosCall("manual-sos-2", "trip-1")),
            actions.manualSosCalls
        )
        assertEquals(0, actions.finishCalls)
        assertEquals(0, actions.validationCalls)
    }

    @Test
    fun noActiveTripDoesNotSendManualSos() = runTest {
        val actions = FakeActions()
        val controller = controller(actions, inactiveStore())

        controller.startHold()
        controller.confirmHeld()

        assertEquals(0, actions.manualSosCalls.size)
        assertTrue(controller.state.value is WearManualSosUiState.Error)
    }

    private fun controller(
        actions: FakeActions,
        store: WearTripStateStore,
        ids: List<String> = listOf("manual-sos-1")
    ): WearManualSosUiController {
        val commandIds = ArrayDeque(ids)
        return WearManualSosUiController(actions, store) { commandIds.removeFirst() }
    }

    private fun activeStore() = WearTripStateStore().also {
        it.confirm(TripStateResponse("state", true, "trip-1", 1L, 2L))
    }

    private fun inactiveStore() = WearTripStateStore().also {
        it.confirm(TripStateResponse("state", false, null, null, 2L))
    }

    private data class ManualSosCall(val commandId: String, val remoteTripId: String)

    private class FakeActions(
        private val results: ArrayDeque<MobileCompanionResult<PhoneActionResponse>> =
            ArrayDeque(listOf(response(PhoneActionResult.OK))),
        private val gate: CompletableDeferred<Unit>? = null
    ) : MobileCompanionClient {
        val manualSosCalls = mutableListOf<ManualSosCall>()
        var finishCalls = 0
        var validationCalls = 0

        override suspend fun getTripState() = MobileCompanionResult.TransportFailure
        override suspend fun startTrip(commandId: String) = MobileCompanionResult.TransportFailure
        override suspend fun finishTrip(commandId: String, remoteTripId: String): MobileCompanionResult<PhoneActionResponse> {
            finishCalls += 1
            return MobileCompanionResult.TransportFailure
        }
        override suspend fun manualSos(commandId: String, remoteTripId: String): MobileCompanionResult<PhoneActionResponse> {
            manualSosCalls += ManualSosCall(commandId, remoteTripId)
            gate?.await()
            return if (results.isEmpty()) response(PhoneActionResult.OK) else results.removeFirst()
        }
    }

    private companion object {
        fun response(result: PhoneActionResult) = MobileCompanionResult.Success(
            PhoneActionResponse("request", result, respondedAtEpochMs = 1L)
        )
    }
}
