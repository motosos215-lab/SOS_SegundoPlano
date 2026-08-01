package com.example.sos_segundoplano.domain.signals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class BatteryConnectivityWearablePolicyTest {
    @Test fun validatesBatteryPercentage() {
        assertEquals(50, BatteryPolicy.percentage(1, 2))
        assertNull(BatteryPolicy.percentage(-1, 100))
        assertNull(BatteryPolicy.percentage(1, 0))
    }

    @Test fun mapsConnectivityWithoutValidatedInternet() {
        val reading = ConnectivityPolicy.reading(true, false, true, NetworkTransport.Cellular, 10L)
        assertSame(SignalAvailability.Available, reading.availability)
        assertEquals(true, reading.sample!!.connected)
        assertEquals(false, reading.sample!!.validated)
        assertEquals(NetworkTransport.Cellular, reading.sample!!.transport)
    }

    @Test fun mapsDisconnectedConnectivityToNone() {
        val reading = ConnectivityPolicy.reading(false, true, true, NetworkTransport.Wifi, 10L)
        assertEquals(false, reading.sample!!.connected)
        assertEquals(false, reading.sample!!.validated)
        assertEquals(NetworkTransport.None, reading.sample!!.transport)
    }

    @Test fun wearableConnectedNearbyRemoteDisconnectedAndStale() {
        assertSame(WearableStatus.ConnectedNearby, WearableStatusPolicy.fromNodes(true, true, true))
        assertSame(WearableStatus.ConnectedRemote, WearableStatusPolicy.fromNodes(true, true, false))
        assertSame(WearableStatus.Disconnected, WearableStatusPolicy.fromNodes(true, false, false))
        assertSame(WearableStatus.Stale, WearableStatusPolicy.applyFreshness(WearableStatus.Capturing, 1L, 10_000L))
    }

    @Test fun actionableWearPermissionStatusDoesNotBecomeStale() {
        assertSame(
            WearableStatus.PermissionRequired,
            WearableStatusPolicy.applyFreshness(WearableStatus.PermissionRequired, 1L, 10_000L)
        )
    }
}
