package com.example.sos_segundoplano.core.permissions

import org.junit.Assert.assertSame
import org.junit.Test

class NotificationRuntimePermissionPolicyTest {
    private val policy = NotificationRuntimePermissionPolicy()

    @Test fun permissionIsRequiredWhenDeniedOnAndroid13AndNewer() {
        assertSame(
            NotificationRuntimePermissionState.Denied,
            policy.evaluate(sdkInt = 33, permissionGranted = false)
        )
        assertSame(
            NotificationRuntimePermissionState.Denied,
            policy.evaluate(sdkInt = 36, permissionGranted = false)
        )
    }

    @Test fun permissionIsNotRequiredBeforeAndroid13() {
        assertSame(
            NotificationRuntimePermissionState.NotRequired,
            policy.evaluate(sdkInt = 32, permissionGranted = false)
        )
    }

    @Test fun grantedPermissionDoesNotRequestAgain() {
        assertSame(
            NotificationRuntimePermissionState.Granted,
            policy.evaluate(sdkInt = 33, permissionGranted = true)
        )
    }
}
