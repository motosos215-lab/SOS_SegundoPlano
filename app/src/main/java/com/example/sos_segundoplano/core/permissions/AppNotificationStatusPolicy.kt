package com.example.sos_segundoplano.core.permissions

class AppNotificationStatusPolicy {
    fun evaluate(notificationsEnabled: Boolean): AppNotificationStatus = if (notificationsEnabled) {
        AppNotificationStatus.Enabled
    } else {
        AppNotificationStatus.Disabled
    }
}
