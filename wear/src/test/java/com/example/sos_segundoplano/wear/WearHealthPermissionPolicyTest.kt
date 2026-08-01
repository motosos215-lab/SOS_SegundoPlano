package com.example.sos_segundoplano.wear

import android.Manifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WearHealthPermissionPolicyTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test fun currentApiUsesModernForegroundHeartRatePermission() {
        val permissions = WearHealthPermissionPolicy.requiredPermissions(
            WearHealthPermissionRequest(apiLevel = 36, targetSdk = 36, requiresBackgroundHealthAccess = false)
        )

        assertEquals(listOf(WearHealthPermissionPolicy.READ_HEART_RATE), permissions)
        assertFalse(permissions.contains(Manifest.permission.ACTIVITY_RECOGNITION))
    }

    @Test fun legacyApiUsesBodySensorsPermission() {
        assertEquals(
            listOf(Manifest.permission.BODY_SENSORS),
            WearHealthPermissionPolicy.requiredPermissions(
                WearHealthPermissionRequest(apiLevel = 35, targetSdk = 36, requiresBackgroundHealthAccess = false)
            )
        )
    }

    @Test fun backgroundPermissionIsOnlyRequiredWhenConfigured() {
        val permissions = WearHealthPermissionPolicy.requiredPermissions(
            WearHealthPermissionRequest(apiLevel = 36, targetSdk = 36, requiresBackgroundHealthAccess = true)
        )

        assertEquals(
            listOf(
                WearHealthPermissionPolicy.READ_HEART_RATE,
                WearHealthPermissionPolicy.READ_HEALTH_DATA_IN_BACKGROUND
            ),
            permissions
        )
        assertEquals(
            WearPermissionStatus.PermissionRequired,
            WearHealthPermissionPolicy.evaluate(
                permissions,
                setOf(WearHealthPermissionPolicy.READ_HEART_RATE)
            )
        )
    }

    @Test fun grantedAndMissingPermissionsProduceExplicitStatus() {
        val required = listOf(WearHealthPermissionPolicy.READ_HEART_RATE)

        assertEquals(
            WearPermissionStatus.Granted,
            WearHealthPermissionPolicy.evaluate(required, setOf(WearHealthPermissionPolicy.READ_HEART_RATE))
        )
        assertEquals(
            WearPermissionStatus.PermissionRequired,
            WearHealthPermissionPolicy.evaluate(required, emptySet())
        )
    }

    @Test fun permanentlyDeniedPermissionProducesExplicitStatus() {
        val required = listOf(WearHealthPermissionPolicy.READ_HEART_RATE)

        assertEquals(
            WearPermissionStatus.PermanentlyDenied,
            WearHealthPermissionPolicy.evaluate(required, emptySet(), setOf(WearHealthPermissionPolicy.READ_HEART_RATE))
        )
    }

    @Test fun heartRateSourceDoesNotStartWithoutPermission() = runTest {
        val client = FakeHeartRateClient()
        val updates = mutableListOf<Pair<WearSignalAvailability, Double?>>()
        val source = WearHeartRateSource(
            onUpdate = { status, bpm -> updates += status to bpm },
            permissionStatus = { WearPermissionStatus.PermissionRequired },
            heartRateClient = client
        )

        source.start()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(WearSignalAvailability.PermissionRequired to null), updates)
        assertFalse(client.started)
        source.release()
        dispatcher.scheduler.advanceUntilIdle()
    }

    @Test fun unavailableHeartRateSensorProducesSensorUnavailable() = runTest {
        val client = FakeHeartRateClient(supportsHeartRate = false)
        val updates = mutableListOf<Pair<WearSignalAvailability, Double?>>()
        val source = WearHeartRateSource({ status, bpm -> updates += status to bpm }, { WearPermissionStatus.Granted }, client)

        source.start()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(updates.contains(WearSignalAvailability.Unsupported to null))
        source.release()
        dispatcher.scheduler.advanceUntilIdle()
    }

    @Test fun unavailableHealthServicesProducesExplicitStatus() = runTest {
        val updates = mutableListOf<Pair<WearSignalAvailability, Double?>>()
        val source = WearHeartRateSource(
            { status, bpm -> updates += status to bpm },
            { WearPermissionStatus.Granted },
            FakeHeartRateClient(available = false)
        )

        source.start()

        assertEquals(listOf(WearSignalAvailability.HealthServicesUnavailable to null), updates)
        source.release()
        dispatcher.scheduler.advanceUntilIdle()
    }

    @Test fun securityExceptionIsMappedToPermissionStatus() = runTest {
        val updates = mutableListOf<Pair<WearSignalAvailability, Double?>>()
        val source = WearHeartRateSource(
            { status, bpm -> updates += status to bpm },
            { WearPermissionStatus.Granted },
            FakeHeartRateClient(throwSecurityOnStart = true)
        )

        source.start()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(updates.contains(WearSignalAvailability.PermissionRequired to null))
        source.release()
        dispatcher.scheduler.advanceUntilIdle()
    }

    @Test fun registrationFailureIsMappedToStartFailed() = runTest {
        val updates = mutableListOf<Pair<WearSignalAvailability, Double?>>()
        val source = WearHeartRateSource(
            { status, bpm -> updates += status to bpm },
            { WearPermissionStatus.Granted },
            FakeHeartRateClient(registrationFails = true)
        )

        source.start()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(WearSignalAvailability.StartFailed to null, updates.last())
        assertFalse(updates.contains(WearSignalAvailability.Waiting to null))
        source.release()
        dispatcher.scheduler.advanceUntilIdle()
    }


    @Test fun revokedPermissionInvalidatesPreviousHeartRate() = runTest {
        var permission = WearPermissionStatus.Granted
        val client = FakeHeartRateClient()
        val updates = mutableListOf<Pair<WearSignalAvailability, Double?>>()
        val source = WearHeartRateSource({ status, bpm -> updates += status to bpm }, { permission }, client)

        source.start()
        dispatcher.scheduler.advanceUntilIdle()
        client.onHeartRate?.invoke(72.0)
        permission = WearPermissionStatus.PermissionRequired
        client.onHeartRate?.invoke(73.0)
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(updates.contains(WearSignalAvailability.Available to 72.0))
        assertTrue(updates.contains(WearSignalAvailability.PermissionRequired to null))
        assertFalse(updates.contains(WearSignalAvailability.Available to 73.0))
        source.release()
        dispatcher.scheduler.advanceUntilIdle()
    }
}

private class FakeHeartRateClient(
    private val available: Boolean = true,
    private val supportsHeartRate: Boolean = true,
    private val throwSecurityOnStart: Boolean = false,
    private val registrationFails: Boolean = false
) : WearHeartRateClient {
    var started = false
    var onHeartRate: ((Double?) -> Unit)? = null
    var released = false

    override fun isAvailable(): Boolean = available
    override suspend fun supportsHeartRate(): Boolean = supportsHeartRate

    override suspend fun start(
        onHeartRate: (Double?) -> Unit,
        onSensorUnavailable: () -> Unit,
        onRegistrationFailed: () -> Unit
    ) {
        if (throwSecurityOnStart) throw SecurityException("permission revoked")
        started = true
        this.onHeartRate = onHeartRate
        if (registrationFails) onRegistrationFailed()
    }

    override suspend fun stop() {
        started = false
    }

    override suspend fun clearCallback() {
        onHeartRate = null
    }

    override fun release() {
        released = true
    }
}
