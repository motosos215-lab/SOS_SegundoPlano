package com.example.sos_segundoplano.core.permissions

class BluetoothRequirementPolicy {
    fun evaluate(
        adapterAvailable: Boolean,
        connectPermissionRequired: Boolean,
        connectPermissionGranted: Boolean,
        adapterEnabled: Boolean
    ): BluetoothRequirementStatus = when {
        !adapterAvailable -> BluetoothRequirementStatus.Unsupported
        connectPermissionRequired && !connectPermissionGranted -> BluetoothRequirementStatus.PermissionMissing
        adapterEnabled -> BluetoothRequirementStatus.Enabled
        else -> BluetoothRequirementStatus.Disabled
    }
}
