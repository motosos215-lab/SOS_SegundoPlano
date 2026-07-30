package com.example.sos_segundoplano.core.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

fun interface BackgroundLocationPermissionStatusProvider {
    fun getStatus(): BackgroundLocationPermissionStatus
}

class BackgroundLocationPermissionChecker(
    context: Context,
    private val policy: BackgroundLocationPermissionPolicy = BackgroundLocationPermissionPolicy()
) : BackgroundLocationPermissionStatusProvider {
    private val appContext = context.applicationContext

    override fun getStatus(): BackgroundLocationPermissionStatus {
        val coarseGranted = appContext.isPermissionGranted(Manifest.permission.ACCESS_COARSE_LOCATION)
        val fineGranted = appContext.isPermissionGranted(Manifest.permission.ACCESS_FINE_LOCATION)
        val backgroundGranted = if (Build.VERSION.SDK_INT >= 29) {
            appContext.isPermissionGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            true
        }

        return policy.evaluate(
            sdkInt = Build.VERSION.SDK_INT,
            coarseGranted = coarseGranted,
            fineGranted = fineGranted,
            backgroundGranted = backgroundGranted
        )
    }

    private fun Context.isPermissionGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}
