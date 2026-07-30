package com.example.sos_segundoplano.core.permissions

import org.junit.Assert.assertSame
import org.junit.Test

class BackgroundLocationPermissionPolicyTest {
    private val policy = BackgroundLocationPermissionPolicy()

    @Test
    fun api28WithoutForegroundPermissionReturnsForegroundMissing() {
        val result = policy.evaluate(28, coarseGranted = false, fineGranted = false, backgroundGranted = false)

        assertSame(BackgroundLocationPermissionStatus.ForegroundMissing, result)
    }

    @Test
    fun api28WithCoarsePermissionReturnsGranted() {
        val result = policy.evaluate(28, coarseGranted = true, fineGranted = false, backgroundGranted = false)

        assertSame(BackgroundLocationPermissionStatus.Granted, result)
    }

    @Test
    fun api28WithFinePermissionReturnsGranted() {
        val result = policy.evaluate(28, coarseGranted = false, fineGranted = true, backgroundGranted = false)

        assertSame(BackgroundLocationPermissionStatus.Granted, result)
    }

    @Test
    fun api29WithCoarseAndMissingBackgroundReturnsBackgroundMissing() {
        val result = policy.evaluate(29, coarseGranted = true, fineGranted = false, backgroundGranted = false)

        assertSame(BackgroundLocationPermissionStatus.BackgroundMissing, result)
    }

    @Test
    fun api29WithFineAndMissingBackgroundReturnsBackgroundMissing() {
        val result = policy.evaluate(29, coarseGranted = false, fineGranted = true, backgroundGranted = false)

        assertSame(BackgroundLocationPermissionStatus.BackgroundMissing, result)
    }

    @Test
    fun api29WithForegroundAndBackgroundReturnsGranted() {
        val result = policy.evaluate(29, coarseGranted = true, fineGranted = false, backgroundGranted = true)

        assertSame(BackgroundLocationPermissionStatus.Granted, result)
    }

    @Test
    fun api30WithForegroundAndMissingBackgroundReturnsBackgroundMissing() {
        val result = policy.evaluate(30, coarseGranted = false, fineGranted = true, backgroundGranted = false)

        assertSame(BackgroundLocationPermissionStatus.BackgroundMissing, result)
    }

    @Test
    fun api30WithForegroundAndBackgroundReturnsGranted() {
        val result = policy.evaluate(30, coarseGranted = true, fineGranted = false, backgroundGranted = true)

        assertSame(BackgroundLocationPermissionStatus.Granted, result)
    }

    @Test
    fun api30WithBackgroundOnlyReturnsForegroundMissing() {
        val result = policy.evaluate(30, coarseGranted = false, fineGranted = false, backgroundGranted = true)

        assertSame(BackgroundLocationPermissionStatus.ForegroundMissing, result)
    }

    @Test
    fun sameInputReturnsSameResult() {
        val first = policy.evaluate(30, coarseGranted = true, fineGranted = false, backgroundGranted = true)
        val second = policy.evaluate(30, coarseGranted = true, fineGranted = false, backgroundGranted = true)

        assertSame(first, second)
    }
}
