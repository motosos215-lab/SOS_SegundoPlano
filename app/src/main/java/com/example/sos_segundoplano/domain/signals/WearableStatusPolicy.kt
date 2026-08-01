package com.example.sos_segundoplano.domain.signals

object WearableStatusPolicy {
    const val STALE_THRESHOLD_MILLIS = 5_000L

    fun fromNodes(hasWearableApi: Boolean, hasNode: Boolean, isNearby: Boolean): WearableStatus = when {
        !hasWearableApi -> WearableStatus.NotInstalledOrUnavailable
        !hasNode -> WearableStatus.Disconnected
        isNearby -> WearableStatus.ConnectedNearby
        else -> WearableStatus.ConnectedRemote
    }

    fun applyFreshness(status: WearableStatus, lastUpdatedMillis: Long, nowMillis: Long): WearableStatus {
        if (lastUpdatedMillis <= 0L) return status
        if (status is WearableStatus.Error || status in terminalStatuses) return status
        return if (nowMillis - lastUpdatedMillis > STALE_THRESHOLD_MILLIS) {
            WearableStatus.Stale
        } else {
            status
        }
    }

    private val terminalStatuses = setOf(
        WearableStatus.Disconnected,
        WearableStatus.PermissionMissing,
        WearableStatus.PermissionRequired,
        WearableStatus.PermanentlyDenied,
        WearableStatus.SensorUnavailable,
        WearableStatus.HealthServicesUnavailable,
        WearableStatus.StartFailed,
        WearableStatus.UserActionRequired,
        WearableStatus.Stopped
    )
}
