package com.example.sos_segundoplano.wear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.concurrent.futures.await
import androidx.core.content.ContextCompat
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

class WearHeartRateSource(
    context: Context,
    private val onUpdate: (WearSignalAvailability, Double?) -> Unit
) {
    private val appContext = context.applicationContext
    private val exerciseClient = HealthServices.getClient(appContext).exerciseClient
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val callback = object : ExerciseUpdateCallback {
        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            val latest = update.latestMetrics.getData(DataType.HEART_RATE_BPM).lastOrNull()?.value
            updateLatest(latest)
        }

        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) = Unit
        override fun onRegistered() = Unit
        override fun onRegistrationFailed(throwable: Throwable) {
            onUpdate(WearSignalAvailability.Error, null)
        }

        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {
            if (dataType == DataType.HEART_RATE_BPM && availability.toString().contains("UNAVAILABLE")) {
                onUpdate(WearSignalAvailability.Unsupported, null)
            }
        }
    }
    private var started = false
    private var exerciseActive = false

    @Synchronized
    fun start() {
        if (started) return
        if (!hasForegroundPermission()) {
            onUpdate(WearSignalAvailability.PermissionMissing, null)
            return
        }
        val config = ExerciseConfig.builder(ExerciseType.BIKING)
            .setDataTypes(setOf(DataType.HEART_RATE_BPM))
            .build()
        started = true
        scope.launch {
            try {
                if (!supportsHeartRate()) {
                    markStartFailed(WearSignalAvailability.Unsupported)
                    return@launch
                }
                exerciseClient.setUpdateCallback(callback)
                exerciseClient.startExerciseAsync(config).await()
                markExerciseStarted()
            } catch (_: SecurityException) {
                markStartFailed(WearSignalAvailability.PermissionMissing)
            } catch (_: IllegalStateException) {
                markStartFailed(WearSignalAvailability.Error)
            } catch (cancellation: CancellationException) {
                throw cancellation
            }
        }
    }

    fun updateLatest(value: Double?) {
        if (value != null && value.isFinite() && value > 0.0) {
            onUpdate(WearSignalAvailability.Available, value)
        }
    }

    @Synchronized
    fun stop() {
        if (!started) return
        val shouldEndExercise = exerciseActive
        started = false
        exerciseActive = false
        scope.launch {
            try {
                if (shouldEndExercise) {
                    exerciseClient.endExerciseAsync().await()
                } else {
                    clearCallback()
                    return@launch
                }
            } catch (_: IllegalStateException) {
            } catch (_: SecurityException) {
            } catch (cancellation: CancellationException) {
                throw cancellation
            } finally {
                withContext(NonCancellable) {
                    clearCallback()
                }
            }
        }
    }

    fun release() {
        scope.cancel()
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
            clearCallback()
            return
        }
        onUpdate(WearSignalAvailability.Waiting, null)
    }

    private suspend fun markStartFailed(status: WearSignalAvailability) {
        synchronized(this) {
            started = false
            exerciseActive = false
        }
        clearCallback()
        onUpdate(status, null)
    }

    private suspend fun clearCallback() {
        try {
            exerciseClient.clearUpdateCallbackAsync(callback).await()
        } catch (_: IllegalStateException) {
        } catch (_: SecurityException) {
        }
    }

    private suspend fun supportsHeartRate(): Boolean {
        val capabilities = exerciseClient.getCapabilitiesAsync().await()
        val exerciseCapabilities = capabilities.getExerciseTypeCapabilities(ExerciseType.BIKING)
        return DataType.HEART_RATE_BPM in exerciseCapabilities.supportedDataTypes
    }

    private fun hasForegroundPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 36) {
            "android.permission.health.READ_HEART_RATE"
        } else {
            Manifest.permission.BODY_SENSORS
        }
        return ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
    }
}
