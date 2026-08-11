package com.example.sos_segundoplano.domain.monitoring

import com.example.sos_segundoplano.core.permissions.AppNotificationStatus
import com.example.sos_segundoplano.core.permissions.BackgroundLocationPermissionStatus
import com.example.sos_segundoplano.core.permissions.BluetoothRequirementStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class MonitoringReadinessFactoryTest {
    @Test fun allCurrentStartRequirementsAvailableIsReady() {
        val readiness = MonitoringReadinessFactory.create(
            BackgroundLocationPermissionStatus.Granted,
            AppNotificationStatus.Enabled,
            BluetoothRequirementStatus.Enabled
        )

        assertEquals(MonitoringReadinessStatus.Ready, readiness.status)
        assertEquals(MonitoringRequirementStatus.Available, readiness.location)
        assertEquals(MonitoringRequirementStatus.Available, readiness.notifications)
        assertEquals(MonitoringRequirementStatus.Available, readiness.bluetooth)
    }

    @Test fun eachMissingCurrentStartRequirementNeedsAttention() {
        val missingLocation = MonitoringReadinessFactory.create(
            BackgroundLocationPermissionStatus.BackgroundMissing,
            AppNotificationStatus.Enabled,
            BluetoothRequirementStatus.Enabled
        )
        val missingNotifications = MonitoringReadinessFactory.create(
            BackgroundLocationPermissionStatus.Granted,
            AppNotificationStatus.Disabled,
            BluetoothRequirementStatus.Enabled
        )
        val disabledBluetooth = MonitoringReadinessFactory.create(
            BackgroundLocationPermissionStatus.Granted,
            AppNotificationStatus.Enabled,
            BluetoothRequirementStatus.Disabled
        )

        assertEquals(MonitoringReadinessStatus.NeedsAttention, missingLocation.status)
        assertEquals(MonitoringReadinessStatus.NeedsAttention, missingNotifications.status)
        assertEquals(MonitoringReadinessStatus.NeedsAttention, disabledBluetooth.status)
    }

    @Test fun unsupportedBluetoothMatchesExistingBlockingPolicyAsNotAvailable() {
        val readiness = MonitoringReadinessFactory.create(
            BackgroundLocationPermissionStatus.Granted,
            AppNotificationStatus.Enabled,
            BluetoothRequirementStatus.Unsupported
        )

        assertEquals(MonitoringRequirementStatus.NotAvailable, readiness.bluetooth)
        assertEquals(MonitoringReadinessStatus.NeedsAttention, readiness.status)
    }
}
