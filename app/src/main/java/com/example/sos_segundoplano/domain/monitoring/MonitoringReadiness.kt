package com.example.sos_segundoplano.domain.monitoring

import com.example.sos_segundoplano.core.permissions.AppNotificationStatus
import com.example.sos_segundoplano.core.permissions.BackgroundLocationPermissionStatus
import com.example.sos_segundoplano.core.permissions.BluetoothRequirementStatus

enum class MonitoringReadinessStatus { Ready, NeedsAttention }

enum class MonitoringRequirementStatus { Available, RequiresAttention, NotAvailable }

data class MonitoringReadiness(
    val location: MonitoringRequirementStatus,
    val notifications: MonitoringRequirementStatus,
    val bluetooth: MonitoringRequirementStatus
) {
    val status: MonitoringReadinessStatus = if (
        location == MonitoringRequirementStatus.Available &&
        notifications == MonitoringRequirementStatus.Available &&
        bluetooth == MonitoringRequirementStatus.Available
    ) {
        MonitoringReadinessStatus.Ready
    } else {
        MonitoringReadinessStatus.NeedsAttention
    }
}

object MonitoringReadinessFactory {
    fun create(
        location: BackgroundLocationPermissionStatus,
        notifications: AppNotificationStatus,
        bluetooth: BluetoothRequirementStatus
    ): MonitoringReadiness = MonitoringReadiness(
        location = when (location) {
            BackgroundLocationPermissionStatus.Granted -> MonitoringRequirementStatus.Available
            BackgroundLocationPermissionStatus.ForegroundMissing,
            BackgroundLocationPermissionStatus.BackgroundMissing -> MonitoringRequirementStatus.RequiresAttention
        },
        notifications = when (notifications) {
            AppNotificationStatus.Enabled -> MonitoringRequirementStatus.Available
            AppNotificationStatus.Disabled -> MonitoringRequirementStatus.RequiresAttention
        },
        bluetooth = when (bluetooth) {
            BluetoothRequirementStatus.Enabled -> MonitoringRequirementStatus.Available
            BluetoothRequirementStatus.PermissionMissing,
            BluetoothRequirementStatus.Disabled -> MonitoringRequirementStatus.RequiresAttention
            BluetoothRequirementStatus.Unsupported -> MonitoringRequirementStatus.NotAvailable
        }
    )
}
