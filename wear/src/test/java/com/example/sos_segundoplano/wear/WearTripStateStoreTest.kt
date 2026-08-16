package com.example.sos_segundoplano.wear

import com.example.sos_segundoplano.wearprotocol.PhoneActionResult
import com.example.sos_segundoplano.wearprotocol.TripStateResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WearTripStateStoreTest {
    @Test fun initialStateIsUnconfirmed() { val s = WearTripStateStore().state.value; assertNull(s.active); assertNull(s.remoteTripId); assertNull(s.startedAtEpochMs) }
    @Test fun confirmedInactiveStateIsStored() { val store = WearTripStateStore(); store.confirm(inactive()); val s = store.state.value; assertFalse(s.active!!); assertNull(s.remoteTripId); assertEquals(100L, s.updatedAtEpochMs) }
    @Test fun confirmedActiveStatePreservesRemoteTripId() { val store = WearTripStateStore(); store.confirm(active()); val s = store.state.value; assertTrue(s.active!!); assertEquals("trip-wear-123", s.remoteTripId); assertEquals(50L, s.startedAtEpochMs) }
    @Test fun confirmedInactiveRefreshClearsPreviousTrip() { val store = WearTripStateStore(); store.confirm(active()); store.confirm(inactive()); assertNull(store.state.value.remoteTripId); assertFalse(store.state.value.active!!) }
    @Test fun disconnectPreservesLastConfirmedActiveTrip() { val store = WearTripStateStore(); store.confirm(active()); store.markDisconnected(); val s=store.state.value; assertTrue(s.active!!); assertEquals("trip-wear-123", s.remoteTripId); assertFalse(s.connected!!) }
    @Test fun refreshActiveStateUpdatesStore() = runBlocking { val store=WearTripStateStore(); val r=WearTripStateReconciler(FakeReader(MobileCompanionResult.Success(active())),store); r.refreshTripState(); assertEquals("trip-wear-123",store.state.value.remoteTripId); assertTrue(store.state.value.active!!) }
    @Test fun refreshInactiveStateClearsTripAfterPhoneConfirmation() = runBlocking { val store=WearTripStateStore(); store.confirm(active()); WearTripStateReconciler(FakeReader(MobileCompanionResult.Success(inactive())),store).refreshTripState(); assertFalse(store.state.value.active!!); assertNull(store.state.value.remoteTripId) }
    @Test fun companionUnavailablePreservesLastConfirmedTrip() = runBlocking { preserves(FakeReader(MobileCompanionResult.CompanionUnavailable)) }
    @Test fun timeoutPreservesLastConfirmedTrip() = runBlocking { preserves(FakeReader(MobileCompanionResult.Timeout)) }
    @Test fun transportFailurePreservesLastConfirmedTrip() = runBlocking { preserves(FakeReader(MobileCompanionResult.TransportFailure)) }
    @Test fun decodeFailurePreservesLastConfirmedTrip() = runBlocking { preserves(FakeReader(MobileCompanionResult.DecodeFailure)) }
    private suspend fun preserves(reader: MobileCompanionClient) { val s=WearTripStateStore(); s.confirm(active()); WearTripStateReconciler(reader,s).refreshTripState(); assertTrue(s.state.value.active!!); assertEquals("trip-wear-123",s.state.value.remoteTripId); assertFalse(s.state.value.connected!!) }
    private fun active()=TripStateResponse("id",true,"trip-wear-123",50L,100L,PhoneActionResult.OK)
    private fun inactive()=TripStateResponse("id",false,null,null,100L,PhoneActionResult.OK)
    private class FakeReader(private val value: MobileCompanionResult<TripStateResponse>): MobileCompanionClient {
        override suspend fun getTripState() = value
        override suspend fun startTrip(commandId: String) = MobileCompanionResult.TransportFailure
        override suspend fun finishTrip(commandId: String, remoteTripId: String) = MobileCompanionResult.TransportFailure
        override suspend fun manualSos(commandId: String, remoteTripId: String) = MobileCompanionResult.TransportFailure
    }
}
