package com.example.sos_segundoplano.core.permissions

class BackgroundLocationPermissionPolicy {
    fun evaluate(
        sdkInt: Int,
        coarseGranted: Boolean,
        fineGranted: Boolean,
        backgroundGranted: Boolean
    ): BackgroundLocationPermissionStatus {
        val foregroundGranted = coarseGranted || fineGranted

        return when {
            !foregroundGranted -> BackgroundLocationPermissionStatus.ForegroundMissing
            sdkInt <= 28 -> BackgroundLocationPermissionStatus.Granted
            backgroundGranted -> BackgroundLocationPermissionStatus.Granted
            else -> BackgroundLocationPermissionStatus.BackgroundMissing
        }
    }
}
