package com.example.sos_segundoplano.core.permissions

import org.junit.Assert.assertSame
import org.junit.Test

class AppNotificationStatusPolicyTest {
    private val policy = AppNotificationStatusPolicy()

    @Test
    fun enabledNotificationsReturnEnabled() {
        val result = policy.evaluate(notificationsEnabled = true)

        assertSame(AppNotificationStatus.Enabled, result)
    }

    @Test
    fun disabledNotificationsReturnDisabled() {
        val result = policy.evaluate(notificationsEnabled = false)

        assertSame(AppNotificationStatus.Disabled, result)
    }

    @Test
    fun sameInputReturnsSameResult() {
        val first = policy.evaluate(notificationsEnabled = false)
        val second = policy.evaluate(notificationsEnabled = false)

        assertSame(first, second)
    }
}
