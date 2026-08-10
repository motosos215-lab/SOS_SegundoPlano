package com.example.sos_segundoplano.core.permissions

enum class NotificationRuntimePermissionState { NotRequired, Granted, Denied }

class NotificationRuntimePermissionPolicy {
    fun evaluate(sdkInt: Int, permissionGranted: Boolean): NotificationRuntimePermissionState = when {
        sdkInt < ANDROID_13_API_LEVEL -> NotificationRuntimePermissionState.NotRequired
        permissionGranted -> NotificationRuntimePermissionState.Granted
        else -> NotificationRuntimePermissionState.Denied
    }

    private companion object {
        const val ANDROID_13_API_LEVEL = 33
    }
}
