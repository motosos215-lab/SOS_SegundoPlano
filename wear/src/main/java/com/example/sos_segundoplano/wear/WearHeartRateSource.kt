package com.example.sos_segundoplano.wear

import android.content.Context
import androidx.concurrent.futures.await
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseUpdate
import androidx.health.services.client.data.ExerciseType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class WearHeartRateSource internal constructor(
    private val onUpdate: (WearSignalAvailability, Double?) -> Unit,
    private val permissionStatus: () -> WearPermissionStatus,
    private val heartRateClient: WearHeartRateClient
) {
    constructor(
        context: Context,
        onUpdate: (WearSignalAvailability, Double?) -> Unit
    ) : this(
        onUpdate = onUpdate,
        permissionStatus = { AndroidWearHealthPermissionChecker(context).status() },
        heartRateClient = AndroidWearHeartRateClient(context)
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false
    private var exerciseActive = false
    private var released = false

    @Synchronized
    fun start() {
        if (started || released) return
        when (permissionStatus()) {
            WearPermissionStatus.Granted -> Unit
            WearPermissionStatus.PermissionRequired -> {
                onUpdate(WearSignalAvailability.PermissionRequired, null)
                return
            }
            WearPermissionStatus.PermanentlyDenied -> {
                onUpdate(WearSignalAvailability.PermanentlyDenied, null)
                return
            }
        }
        if (!heartRateClient.isAvailable()) {
            onUpdate(WearSignalAvailability.HealthServicesUnavailable, null)
            return
        }
        started = true
        scope.launch {
            try {
                if (!heartRateClient.supportsHeartRate()) {
                    markStartFailed(WearSignalAvailability.Unsupported)
                    return@launch
                }
                val startReturned = AtomicBoolean(false)
                val registrationFailedDuringStart = AtomicBoolean(false)
                heartRateClient.start(
                    onHeartRate = ::updateLatest,
                    onSensorUnavailable = { onUpdate(WearSignalAvailability.Unsupported, null) },
                    onRegistrationFailed = {
                        if (startReturned.get()) {
                            markRegistrationFailed()
                        } else {
                            registrationFailedDuringStart.set(true)
                        }
                    }
                )
                startReturned.set(true)
                if (registrationFailedDuringStart.get()) {
                    markStartFailed(WearSignalAvailability.StartFailed)
                    return@launch
                }
                markExerciseStarted()
            } catch (_: SecurityException) {
                markStartFailed(WearSignalAvailability.PermissionRequired)
            } catch (_: UnsupportedOperationException) {
                markStartFailed(WearSignalAvailability.HealthServicesUnavailable)
            } catch (_: IllegalStateException) {
                markStartFailed(WearSignalAvailability.StartFailed)
            } catch (cancellation: CancellationException) {
                throw cancellation
            }
        }
    }

    fun updateLatest(value: Double?) {
        when (permissionStatus()) {
            WearPermissionStatus.Granted -> Unit
            WearPermissionStatus.PermissionRequired -> {
                scope.launch { markStartFailed(WearSignalAvailability.PermissionRequired) }
                return
            }
            WearPermissionStatus.PermanentlyDenied -> {
                scope.launch { markStartFailed(WearSignalAvailability.PermanentlyDenied) }
                return
            }
        }
        if (value != null && value.isFinite() && value > 0.0) {
            onUpdate(WearSignalAvailability.Available, value)
        }
    }

    @Synchronized
    fun stop() {
        stopInternal(cancelAfterStop = false)
    }

    fun release() {
        stopInternal(cancelAfterStop = true)
    }

    @Synchronized
    private fun stopInternal(cancelAfterStop: Boolean) {
        if (released && cancelAfterStop) return
        val shouldEndExercise = exerciseActive
        val shouldClearCallback = started || cancelAfterStop
        if (!started && !cancelAfterStop) return
        if (cancelAfterStop) released = true
        started = false
        exerciseActive = false
        scope.launch {
            try {
                if (shouldEndExercise) {
                    heartRateClient.stop()
                } else if (shouldClearCallback) {
                    heartRateClient.clearCallback()
                }
            } catch (_: IllegalStateException) {
            } catch (_: SecurityException) {
            } catch (cancellation: CancellationException) {
                throw cancellation
            } finally {
                withContext(NonCancellable) {
                    heartRateClient.clearCallback()
                }
                if (cancelAfterStop) {
                    heartRateClient.release()
                    scope.cancel()
                }
            }
        }
    }

    private suspend fun markExerciseStarted() {
        val shouldClear = synchronized(this) {
            if (!started) {
                true
            } else {
                exerciseActive = true
                false
            }
        }
        if (shouldClear) {
            heartRateClient.clearCallback()
            return
        }
        onUpdate(WearSignalAvailability.Waiting, null)
    }

    private suspend fun markStartFailed(status: WearSignalAvailability) {
        synchronized(this) {
            started = false
            exerciseActive = false
        }
        heartRateClient.clearCallback()
        onUpdate(status, null)
    }

    private fun markRegistrationFailed() {
        scope.launch {
            markStartFailed(WearSignalAvailability.StartFailed)
        }
    }
}

internal interface WearHeartRateClient {
    fun isAvailable(): Boolean = true
    suspend fun supportsHeartRate(): Boolean
    suspend fun start(
        onHeartRate: (Double?) -> Unit,
        onSensorUnavailable: () -> Unit,
        onRegistrationFailed: () -> Unit
    )
    suspend fun stop()
    suspend fun clearCallback()
    fun release() = Unit
}

internal class AndroidWearHeartRateClient(context: Context) : WearHeartRateClient {
    private val exerciseClient = HealthServices.getClient(context.applicationContext).exerciseClient
    private var callback: ExerciseUpdateCallback? = null

    override suspend fun supportsHeartRate(): Boolean {
        val capabilities = exerciseClient.getCapabilitiesAsync().await()
        val exerciseCapabilities = capabilities.getExerciseTypeCapabilities(ExerciseType.BIKING)
        return DataType.HEART_RATE_BPM in exerciseCapabilities.supportedDataTypes
    }

    override suspend fun start(
        onHeartRate: (Double?) -> Unit,
        onSensorUnavailable: () -> Unit,
        onRegistrationFailed: () -> Unit
    ) {
        val nextCallback = object : ExerciseUpdateCallback {
            override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
                onHeartRate(update.latestMetrics.getData(DataType.HEART_RATE_BPM).lastOrNull()?.value)
            }

            override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) = Unit
            override fun onRegistered() = Unit
            override fun onRegistrationFailed(throwable: Throwable) = onRegistrationFailed()

            override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {
                if (dataType == DataType.HEART_RATE_BPM && availability.toString().contains("UNAVAILABLE")) {
                    onSensorUnavailable()
                }
            }
        }
        callback = nextCallback
        val config = ExerciseConfig.builder(ExerciseType.BIKING)
            .setDataTypes(setOf(DataType.HEART_RATE_BPM))
            .build()
        exerciseClient.setUpdateCallback(nextCallback)
        exerciseClient.startExerciseAsync(config).await()
    }

    override suspend fun stop() {
        exerciseClient.endExerciseAsync().await()
    }

    override suspend fun clearCallback() {
        val currentCallback = callback ?: return
        try {
            exerciseClient.clearUpdateCallbackAsync(currentCallback).await()
        } catch (_: IllegalStateException) {
        } catch (_: SecurityException) {
        } finally {
            callback = null
        }
    }
}
