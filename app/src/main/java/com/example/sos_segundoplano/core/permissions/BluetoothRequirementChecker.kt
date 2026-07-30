package com.example.sos_segundoplano.core.permissions

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

fun interface BluetoothRequirementStatusProvider {
    fun getStatus(): BluetoothRequirementStatus
}

class BluetoothRequirementChecker(
    context: Context,
    private val policy: BluetoothRequirementPolicy = BluetoothRequirementPolicy()
) : BluetoothRequirementStatusProvider {
    private val appContext = context.applicationContext

    override fun getStatus(): BluetoothRequirementStatus {
        val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
        val adapter = bluetoothManager?.adapter
        val adapterAvailable = adapter != null
        val connectPermissionRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val connectPermissionGranted = !connectPermissionRequired ||
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED

        if (!adapterAvailable) {
            return policy.evaluate(
                adapterAvailable = false,
                connectPermissionRequired = connectPermissionRequired,
                connectPermissionGranted = connectPermissionGranted,
                adapterEnabled = false
            )
        }

        if (connectPermissionRequired && !connectPermissionGranted) {
            return policy.evaluate(
                adapterAvailable = true,
                connectPermissionRequired = true,
                connectPermissionGranted = false,
                adapterEnabled = false
            )
        }

        val adapterEnabled = try {
            adapter?.isEnabled == true
        } catch (_: SecurityException) {
            return BluetoothRequirementStatus.PermissionMissing
        }

        return policy.evaluate(
            adapterAvailable = true,
            connectPermissionRequired = connectPermissionRequired,
            connectPermissionGranted = connectPermissionGranted,
            adapterEnabled = adapterEnabled
        )
    }
}
