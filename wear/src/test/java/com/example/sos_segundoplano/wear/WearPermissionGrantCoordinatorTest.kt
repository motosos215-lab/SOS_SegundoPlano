package com.example.sos_segundoplano.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WearPermissionGrantCoordinatorTest {
    @Test fun grantedPermissionWithActiveTripStartsCaptureOnceAndFinishes() {
        var launches = 0
        val resolution = coordinator(
            status = WearPermissionStatus.Granted,
            activeTrip = true,
            launch = { launches += 1; WearSignalCaptureLaunchResult.Started }
        ).resolve()

        assertEquals(1, launches)
        assertEquals(WearPermissionStatus.Granted, resolution.status)
        assertTrue(resolution.finishPermissionActivity)
        assertFalse(resolution.retryCapture)
    }

    @Test fun grantedPermissionWithoutActiveTripFinishesWithoutStartingCapture() {
        var launches = 0
        val resolution = coordinator(
            status = WearPermissionStatus.Granted,
            activeTrip = false,
            launch = { launches += 1; WearSignalCaptureLaunchResult.Started }
        ).resolve()

        assertEquals(0, launches)
        assertTrue(resolution.finishPermissionActivity)
    }

    @Test fun stalePermissionRequiredIsReplacedByRealGrantedStatus() {
        val resolution = coordinator(
            status = WearPermissionStatus.Granted,
            activeTrip = true,
            launch = { WearSignalCaptureLaunchResult.Started }
        ).resolve()

        assertEquals(WearPermissionStatus.Granted, resolution.status)
        assertTrue(resolution.finishPermissionActivity)
    }

    @Test fun permanentlyDeniedDoesNotLaunchOrFinishInALoop() {
        var launches = 0
        val resolution = coordinator(
            status = WearPermissionStatus.PermanentlyDenied,
            activeTrip = true,
            launch = { launches += 1; WearSignalCaptureLaunchResult.Started }
        ).resolve()

        assertEquals(0, launches)
        assertEquals(WearPermissionStatus.PermanentlyDenied, resolution.status)
        assertFalse(resolution.finishPermissionActivity)
    }

    @Test fun foregroundLaunchFailureKeepsOnlyARealCaptureRetry() {
        val resolution = coordinator(
            status = WearPermissionStatus.Granted,
            activeTrip = true,
            launch = { WearSignalCaptureLaunchResult.StartNotAllowed }
        ).resolve()

        assertEquals(WearPermissionStatus.Granted, resolution.status)
        assertFalse(resolution.finishPermissionActivity)
        assertTrue(resolution.retryCapture)
    }

    private fun coordinator(
        status: WearPermissionStatus,
        activeTrip: Boolean,
        launch: () -> WearSignalCaptureLaunchResult
    ) = WearPermissionGrantCoordinator(
        permissionStatus = { status },
        hasConfirmedActiveTrip = { activeTrip },
        requestStartCapture = launch
    )
}
