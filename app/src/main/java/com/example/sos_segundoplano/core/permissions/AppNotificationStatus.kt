package com.example.sos_segundoplano.core.permissions

sealed interface AppNotificationStatus {
    data object Enabled : AppNotificationStatus
    data object Disabled : AppNotificationStatus
}
