package com.example.sos_segundoplano.data.route

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.sos_segundoplano.BuildConfig
import com.example.sos_segundoplano.core.auth.AuthProvider
import com.example.sos_segundoplano.data.remote.trip.TripRoutePointUploadDto
import com.example.sos_segundoplano.data.remote.trip.TripRoutePointsBatchRequestDto
import com.example.sos_segundoplano.domain.auth.AuthFailure
import com.example.sos_segundoplano.domain.auth.AuthResult
import com.example.sos_segundoplano.domain.auth.SessionState
import com.example.sos_segundoplano.domain.auth.UserRole
import java.io.IOException

class TripRouteSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val dependencies = TripRouteProvider.get(applicationContext)
        val auth = AuthProvider.get(applicationContext)
        val session = auth.observeSession().value
        val user = when (session) {
            is SessionState.Authenticated -> session.user
            is SessionState.Refreshing -> session.user
            else -> null
        }
        if (user?.role != UserRole.Rider || user.id.isBlank()) return Result.success()

        val token = when (val tokenResult = auth.ensureValidAccessToken()) {
            is AuthResult.Success -> tokenResult.value.reveal()
            is AuthFailure -> return Result.retry()
        }
        val authorization = "Bearer $token"
        val api = dependencies.api
        val dao = dependencies.database.routePointDao()
        val tripIds = dao.pendingTripIds(user.id)
        if (tripIds.isEmpty()) return Result.success()

        for (tripId in tripIds) {
            while (true) {
                val batch = dao.pendingBatch(user.id, tripId, MAX_BATCH_SIZE)
                if (batch.isEmpty()) break
                val request = TripRoutePointsBatchRequestDto(batch.map { it.toUploadDto() })
                val response = try {
                    api.uploadRoutePoints(authorization, tripId, request)
                } catch (_: IOException) {
                    return Result.retry()
                } catch (_: Exception) {
                    return Result.retry()
                }

                when {
                    response.isSuccessful && response.body()?.success == true -> {
                        // Delete only after the API envelope confirms success. The same UUID + payload
                        // remains intact for every retry, matching backend idempotency semantics.
                        dao.deleteByIds(batch.map { it.clientRoutePointId })
                        Log.d(TAG, "route batch synced trip=$tripId points=${batch.size}")
                    }
                    response.isSuccessful -> {
                        Log.w(TAG, "route batch invalid envelope trip=$tripId")
                        return Result.retry()
                    }
                    response.code() == 401 || response.code() == 408 || response.code() == 429 || response.code() >= 500 -> return Result.retry()
                    else -> {
                        // 403/404/409 are not retried forever. The local rows are intentionally kept
                        // for diagnosis/reconciliation instead of being silently discarded.
                        Log.w(TAG, "route batch rejected trip=$tripId http=${response.code()}")
                        return Result.failure()
                    }
                }
            }
        }
        return Result.success()
    }

    private fun TripRoutePointEntity.toUploadDto() = TripRoutePointUploadDto(
        clientRoutePointId = clientRoutePointId,
        sequence = sequence,
        recordedAtUtc = recordedAtUtc,
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracyMeters,
        speedMetersPerSecond = speedMetersPerSecond,
        bearingDegrees = bearingDegrees,
        appVersion = BuildConfig.VERSION_NAME,
        deviceId = null
    )

    companion object {
        private const val MAX_BATCH_SIZE = 500
        private const val TAG = "MotoSOS.TripRoute"
    }
}
