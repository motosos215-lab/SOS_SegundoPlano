package com.example.sos_segundoplano.core.permissions

sealed interface BackgroundLocationPermissionStatus {
    data object Granted : BackgroundLocationPermissionStatus
    data object ForegroundMissing : BackgroundLocationPermissionStatus
    data object BackgroundMissing : BackgroundLocationPermissionStatus
}
