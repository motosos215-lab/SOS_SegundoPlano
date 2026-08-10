package com.example.sos_segundoplano.core.permissions

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

fun interface AppNotificationStatusProvider {
    fun getStatus(): AppNotificationStatus
}

class AppNotificationStatusChecker(
    context: Context,
    private val policy: AppNotificationStatusPolicy = AppNotificationStatusPolicy()
) : AppNotificationStatusProvider {
    private val appContext = context.applicationContext

    override fun getStatus(): AppNotificationStatus = policy.evaluate(
        notificationsEnabled = NotificationManagerCompat.from(appContext).areNotificationsEnabled() &&
            runtimePermissionGranted()
    )

    private fun runtimePermissionGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
}
