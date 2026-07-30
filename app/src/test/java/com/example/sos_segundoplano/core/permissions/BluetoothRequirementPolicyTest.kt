package com.example.sos_segundoplano.core.permissions

import org.junit.Assert.assertSame
import org.junit.Test

class BluetoothRequirementPolicyTest {
    private val policy = BluetoothRequirementPolicy()

    @Test
    fun missingAdapterReturnsUnsupported() {
        val result = policy.evaluate(
            adapterAvailable = false,
            connectPermissionRequired = false,
            connectPermissionGranted = true,
            adapterEnabled = true
        )

        assertSame(BluetoothRequirementStatus.Unsupported, result)
    }

    @Test
    fun requiredMissingPermissionReturnsPermissionMissing() {
        val result = policy.evaluate(
            adapterAvailable = true,
            connectPermissionRequired = true,
            connectPermissionGranted = false,
            adapterEnabled = true
        )

        assertSame(BluetoothRequirementStatus.PermissionMissing, result)
    }

    @Test
    fun requiredGrantedPermissionAndDisabledAdapterReturnsDisabled() {
        val result = policy.evaluate(
            adapterAvailable = true,
            connectPermissionRequired = true,
            connectPermissionGranted = true,
            adapterEnabled = false
        )

        assertSame(BluetoothRequirementStatus.Disabled, result)
    }

    @Test
    fun requiredGrantedPermissionAndEnabledAdapterReturnsEnabled() {
        val result = policy.evaluate(
            adapterAvailable = true,
            connectPermissionRequired = true,
            connectPermissionGranted = true,
            adapterEnabled = true
        )

        assertSame(BluetoothRequirementStatus.Enabled, result)
    }

    @Test
    fun notRequiredPermissionAndEnabledAdapterReturnsEnabled() {
        val result = policy.evaluate(
            adapterAvailable = true,
            connectPermissionRequired = false,
            connectPermissionGranted = false,
            adapterEnabled = true
        )

        assertSame(BluetoothRequirementStatus.Enabled, result)
    }

    @Test
    fun missingAdapterHasPriorityOverMissingPermission() {
        val result = policy.evaluate(
            adapterAvailable = false,
            connectPermissionRequired = true,
            connectPermissionGranted = false,
            adapterEnabled = true
        )

        assertSame(BluetoothRequirementStatus.Unsupported, result)
    }

    @Test
    fun sameInputReturnsSameResult() {
        val first = policy.evaluate(
            adapterAvailable = true,
            connectPermissionRequired = true,
            connectPermissionGranted = true,
            adapterEnabled = false
        )
        val second = policy.evaluate(
            adapterAvailable = true,
            connectPermissionRequired = true,
            connectPermissionGranted = true,
            adapterEnabled = false
        )

        assertSame(first, second)
    }
}
