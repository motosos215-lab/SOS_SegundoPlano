package com.example.sos_segundoplano.core.permissions

import android.content.Context
import androidx.core.app.NotificationManagerCompat

fun interface AppNotificationStatusProvider {
    fun getStatus(): AppNotificationStatus
}

class AppNotificationStatusChecker(
    context: Context,
    private val policy: AppNotificationStatusPolicy = AppNotificationStatusPolicy()
) : AppNotificationStatusProvider {
    private val appContext = context.applicationContext

    override fun getStatus(): AppNotificationStatus = policy.evaluate(
        notificationsEnabled = NotificationManagerCompat.from(appContext).areNotificationsEnabled()
    )
}
