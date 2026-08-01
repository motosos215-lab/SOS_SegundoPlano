package com.example.sos_segundoplano.wear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

enum class WearPermissionStatus { Granted, PermissionRequired, PermanentlyDenied }

data class WearHealthPermissionRequest(
    val apiLevel: Int,
    val targetSdk: Int,
    val requiresBackgroundHealthAccess: Boolean
)

object WearHealthPermissionPolicy {
    const val READ_HEART_RATE = "android.permission.health.READ_HEART_RATE"
    const val READ_HEALTH_DATA_IN_BACKGROUND = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"

    fun requiredPermissions(request: WearHealthPermissionRequest): List<String> {
        val permissions = mutableListOf<String>()
        if (usesModernHealthPermissions(request)) {
            permissions += READ_HEART_RATE
            if (request.requiresBackgroundHealthAccess) {
                permissions += READ_HEALTH_DATA_IN_BACKGROUND
            }
        } else {
            permissions += Manifest.permission.BODY_SENSORS
            if (request.requiresBackgroundHealthAccess && request.apiLevel >= 33) {
                permissions += Manifest.permission.BODY_SENSORS_BACKGROUND
            }
        }
        return permissions
    }

    fun evaluate(
        requiredPermissions: List<String>,
        grantedPermissions: Set<String>,
        permanentlyDeniedPermissions: Set<String> = emptySet()
    ): WearPermissionStatus {
        val missing = requiredPermissions.filterNot { it in grantedPermissions }
        if (missing.isEmpty()) return WearPermissionStatus.Granted
        return if (missing.any { it in permanentlyDeniedPermissions }) {
            WearPermissionStatus.PermanentlyDenied
        } else {
            WearPermissionStatus.PermissionRequired
        }
    }

    private fun usesModernHealthPermissions(request: WearHealthPermissionRequest): Boolean =
        request.apiLevel >= 36 && request.targetSdk >= 36
}

class AndroidWearHealthPermissionChecker(
    private val context: Context,
    private val requiresBackgroundHealthAccess: Boolean = false
) {
    private val appContext = context.applicationContext

    fun requiredPermissions(): List<String> = WearHealthPermissionPolicy.requiredPermissions(currentRequest())

    fun missingPermissions(): List<String> = requiredPermissions().filterNot { permission ->
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun status(permanentlyDeniedPermissions: Set<String> = emptySet()): WearPermissionStatus {
        val granted = requiredPermissions().filter { permission ->
            ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
        }.toSet()
        return WearHealthPermissionPolicy.evaluate(requiredPermissions(), granted, permanentlyDeniedPermissions)
    }

    fun permanentlyDeniedPermissions(activity: android.app.Activity, deniedPermissions: Set<String>): Set<String> =
        deniedPermissions.filterNot { permission ->
            ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
        }.toSet()

    private fun currentRequest(): WearHealthPermissionRequest = WearHealthPermissionRequest(
        apiLevel = Build.VERSION.SDK_INT,
        targetSdk = appContext.applicationInfo.targetSdkVersion,
        requiresBackgroundHealthAccess = requiresBackgroundHealthAccess
    )
}
