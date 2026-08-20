package com.example.sos_segundoplano.data.offline

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.sos_segundoplano.data.remote.incident.IncidentRemoteProvider
import com.example.sos_segundoplano.domain.validation.IncidentRemoteCreationStatus

/**
 * Durable recovery for a manual SOS that was already persisted in RemoteIncidentLinkStore.
 * This worker never creates a new SOS; it only retries the existing clientIncidentId /
 * clientAlertRequestId pair after Wi-Fi or cellular connectivity returns.
 */
class ManualSosSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val result = IncidentRemoteProvider.retryPendingManualSosAwait(applicationContext)
            ?: return Result.success()

        return when (val status = result.remoteCreationStatus) {
            is IncidentRemoteCreationStatus.Success -> Result.success()
            is IncidentRemoteCreationStatus.NetworkUnavailable,
            is IncidentRemoteCreationStatus.Timeout,
            is IncidentRemoteCreationStatus.MissingRequiredData,
            IncidentRemoteCreationStatus.Pending,
            IncidentRemoteCreationStatus.NotRequested,
            IncidentRemoteCreationStatus.DuplicateAttempt -> Result.retry()

            is IncidentRemoteCreationStatus.HttpError -> {
                if (status.statusCode == 401 || status.statusCode == 408 || status.statusCode == 409 || status.statusCode == 429 || status.statusCode >= 500) {
                    Result.retry()
                } else {
                    Result.success()
                }
            }

            is IncidentRemoteCreationStatus.InvalidResponse -> {
                if (status.sanitizedMessage in setOf("access_token_invalid", "manual_sos_result_persistence_failed")) {
                    Result.retry()
                } else {
                    Result.success()
                }
            }
        }
    }
}
