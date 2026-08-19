package com.example.sos_segundoplano.wear

import android.content.Context
import androidx.core.content.ContextCompat

internal enum class WearSignalCaptureLaunchResult {
    Started,
    PermissionRequired,
    PermanentlyDenied,
    StartNotAllowed
}

/**
 * The only boundary allowed to create the health foreground service. It checks permission before
 * [ContextCompat.startForegroundService], because the service cannot safely stop itself before
 * calling startForeground after that API has already been invoked.
 */
internal class WearSignalCaptureLauncher(
    private val permissionStatus: () -> WearPermissionStatus,
    private val startForegroundService: () -> Unit,
    private val publishCaptureStatus: (WearCaptureStatus) -> Unit
) {
    fun requestStartCapture(): WearSignalCaptureLaunchResult = when (permissionStatus()) {
        WearPermissionStatus.PermissionRequired -> {
            publishCaptureStatus(WearCaptureStatus.PermissionRequired)
            WearSignalCaptureLaunchResult.PermissionRequired
        }
        WearPermissionStatus.PermanentlyDenied -> {
            publishCaptureStatus(WearCaptureStatus.PermanentlyDenied)
            WearSignalCaptureLaunchResult.PermanentlyDenied
        }
        WearPermissionStatus.Granted -> try {
            startForegroundService()
            WearSignalCaptureLaunchResult.Started
        } catch (_: IllegalStateException) {
            publishCaptureStatus(WearCaptureStatus.UserActionRequired)
            WearSignalCaptureLaunchResult.StartNotAllowed
        } catch (_: SecurityException) {
            publishCaptureStatus(WearCaptureStatus.PermissionRequired)
            WearSignalCaptureLaunchResult.PermissionRequired
        }
    }

    fun reconcileActiveTrip(active: Boolean): WearSignalCaptureLaunchResult? =
        if (active) requestStartCapture() else null

    companion object {
        fun create(context: Context): WearSignalCaptureLauncher {
            val appContext = context.applicationContext
            val permissionChecker = AndroidWearHealthPermissionChecker(appContext)
            return WearSignalCaptureLauncher(
                permissionStatus = permissionChecker::status,
                startForegroundService = {
                    ContextCompat.startForegroundService(
                        appContext,
                        WearSignalForegroundService.createStartIntent(appContext)
                    )
                },
                publishCaptureStatus = { status ->
                    WearSignalForegroundService.publishCaptureStatus(appContext, status)
                }
            )
        }
    }
}
