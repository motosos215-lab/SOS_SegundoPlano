package com.example.sos_segundoplano.wear

import org.junit.Assert.assertEquals
import org.junit.Test

class WearSignalCaptureLauncherTest {
    @Test fun grantedPermissionStartsForegroundCaptureExactlyOnce() {
        var starts = 0
        val statuses = mutableListOf<WearCaptureStatus>()
        val launcher = launcher(WearPermissionStatus.Granted, { starts += 1 }, statuses)

        assertEquals(WearSignalCaptureLaunchResult.Started, launcher.requestStartCapture())
        assertEquals(1, starts)
        assertEquals(emptyList<WearCaptureStatus>(), statuses)
    }

    @Test fun permissionRequiredDoesNotCreateForegroundService() {
        var starts = 0
        val statuses = mutableListOf<WearCaptureStatus>()
        val launcher = launcher(WearPermissionStatus.PermissionRequired, { starts += 1 }, statuses)

        assertEquals(WearSignalCaptureLaunchResult.PermissionRequired, launcher.requestStartCapture())
        assertEquals(0, starts)
        assertEquals(listOf(WearCaptureStatus.PermissionRequired), statuses)
    }

    @Test fun permanentlyDeniedDoesNotCreateForegroundService() {
        var starts = 0
        val statuses = mutableListOf<WearCaptureStatus>()
        val launcher = launcher(WearPermissionStatus.PermanentlyDenied, { starts += 1 }, statuses)

        assertEquals(WearSignalCaptureLaunchResult.PermanentlyDenied, launcher.requestStartCapture())
        assertEquals(0, starts)
        assertEquals(listOf(WearCaptureStatus.PermanentlyDenied), statuses)
    }

    @Test fun foregroundStartNotAllowedIsContainedWithoutClaimingCaptureStarted() {
        val statuses = mutableListOf<WearCaptureStatus>()
        val launcher = launcher(
            WearPermissionStatus.Granted,
            { throw IllegalStateException("background start not allowed") },
            statuses
        )

        assertEquals(WearSignalCaptureLaunchResult.StartNotAllowed, launcher.requestStartCapture())
        assertEquals(listOf(WearCaptureStatus.UserActionRequired), statuses)
    }

    @Test fun activeTripWithFreshSignalStateDerivesPermissionRequiredAgain() {
        var starts = 0
        val statuses = mutableListOf<WearCaptureStatus>()
        val launcher = launcher(WearPermissionStatus.PermissionRequired, { starts += 1 }, statuses)

        assertEquals(WearSignalCaptureLaunchResult.PermissionRequired, launcher.reconcileActiveTrip(active = true))
        assertEquals(0, starts)
        assertEquals(listOf(WearCaptureStatus.PermissionRequired), statuses)
    }

    @Test fun grantingPermissionDuringAnActiveTripStartsCaptureWithoutStartingTripAgain() {
        var permission = WearPermissionStatus.PermissionRequired
        var starts = 0
        val statuses = mutableListOf<WearCaptureStatus>()
        val launcher = WearSignalCaptureLauncher(
            permissionStatus = { permission },
            startForegroundService = { starts += 1 },
            publishCaptureStatus = { statuses += it }
        )

        launcher.reconcileActiveTrip(active = true)
        permission = WearPermissionStatus.Granted
        assertEquals(WearSignalCaptureLaunchResult.Started, launcher.reconcileActiveTrip(active = true))

        assertEquals(1, starts)
        assertEquals(listOf(WearCaptureStatus.PermissionRequired), statuses)
    }

    private fun launcher(
        permission: WearPermissionStatus,
        start: () -> Unit,
        statuses: MutableList<WearCaptureStatus>
    ) = WearSignalCaptureLauncher(
        permissionStatus = { permission },
        startForegroundService = start,
        publishCaptureStatus = { statuses += it }
    )
}
