package com.example.sos_segundoplano.core.permissions

sealed interface BluetoothRequirementStatus {
    data object Enabled : BluetoothRequirementStatus
    data object Disabled : BluetoothRequirementStatus
    data object PermissionMissing : BluetoothRequirementStatus
    data object Unsupported : BluetoothRequirementStatus
}
