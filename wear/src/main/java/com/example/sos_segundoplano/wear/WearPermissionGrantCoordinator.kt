package com.example.sos_segundoplano.wear

internal data class WearPermissionGrantResolution(
    val status: WearPermissionStatus,
    val finishPermissionActivity: Boolean,
    val retryCapture: Boolean = false
)

/** Resolves the real permission state after a system permission callback. */
internal class WearPermissionGrantCoordinator(
    private val permissionStatus: (Set<String>) -> WearPermissionStatus,
    private val hasConfirmedActiveTrip: () -> Boolean,
    private val requestStartCapture: () -> WearSignalCaptureLaunchResult
) {
    fun resolve(permanentlyDeniedPermissions: Set<String> = emptySet()): WearPermissionGrantResolution {
        return when (val status = permissionStatus(permanentlyDeniedPermissions)) {
            WearPermissionStatus.PermissionRequired -> WearPermissionGrantResolution(status, false)
            WearPermissionStatus.PermanentlyDenied -> WearPermissionGrantResolution(status, false)
            WearPermissionStatus.Granted -> {
                if (!hasConfirmedActiveTrip()) {
                    WearPermissionGrantResolution(status, finishPermissionActivity = true)
                } else when (requestStartCapture()) {
                    WearSignalCaptureLaunchResult.Started ->
                        WearPermissionGrantResolution(status, finishPermissionActivity = true)
                    WearSignalCaptureLaunchResult.PermissionRequired ->
                        WearPermissionGrantResolution(WearPermissionStatus.PermissionRequired, false)
                    WearSignalCaptureLaunchResult.PermanentlyDenied ->
                        WearPermissionGrantResolution(WearPermissionStatus.PermanentlyDenied, false)
                    WearSignalCaptureLaunchResult.StartNotAllowed ->
                        WearPermissionGrantResolution(status, false, retryCapture = true)
                }
            }
        }
    }
}
