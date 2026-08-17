package com.example.sos_segundoplano.core.push

import android.os.Build
import com.example.sos_segundoplano.BuildConfig
import com.example.sos_segundoplano.data.remote.auth.AuthNetworkFactory
import com.example.sos_segundoplano.data.remote.push.RetrofitPushTokenRemoteDataSource
import com.example.sos_segundoplano.data.repository.DefaultPushTokenRepository
import com.example.sos_segundoplano.data.repository.PushAppMetadata
import com.example.sos_segundoplano.data.repository.PushAppMetadataProvider
import com.example.sos_segundoplano.domain.push.PushTokenRepository
import com.example.sos_segundoplano.domain.push.PushTokenStore
import com.example.sos_segundoplano.domain.push.PushTokenSyncResult
import com.example.sos_segundoplano.domain.auth.AuthSessionIdentity
import com.example.sos_segundoplano.domain.repository.AuthRepository
import com.example.sos_segundoplano.push.PushDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object PushTokenRegistrationProvider {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var repository: PushTokenRepository? = null

    fun initialize(
        authRepository: AuthRepository,
        store: PushTokenStore
    ) {
        if (repository == null) synchronized(this) {
            if (repository == null) {
                val moshi = AuthNetworkFactory.createMoshi()
                val api = AuthNetworkFactory.createPushNotificationTokensApi(
                    BuildConfig.MOTOSOS_API_BASE_URL,
                    moshi
                )
                repository = DefaultPushTokenRepository(
                    authRepository = authRepository,
                    store = store,
                    remoteDataSource = RetrofitPushTokenRemoteDataSource(api, moshi),
                    metadataProvider = PushAppMetadataProvider {
                        PushAppMetadata(
                            appVersion = BuildConfig.VERSION_NAME,
                            osVersion = Build.VERSION.RELEASE
                        )
                    }
                )
            }
        }
        scheduleSync()
    }

    fun scheduleSync() {
        val installed = repository ?: return
        applicationScope.launch {
            PushDiagnostics.debug(PushDiagnostics.syncStarted())
            val result = installed.syncPendingMonitorToken()
            PushDiagnostics.debug(PushDiagnostics.syncResult(result.diagnosticResult(), result.diagnosticCode()))
        }
    }

    suspend fun revokeBeforeLogout(ownerSession: AuthSessionIdentity): PushTokenSyncResult =
        repository?.revokeMonitorRegistration(ownerSession)
        ?: PushTokenSyncResult.NothingPending

}

private fun PushTokenSyncResult.diagnosticResult(): String = when (this) {
    PushTokenSyncResult.Registered -> "success"
    PushTokenSyncResult.NothingPending -> "nothing_pending"
    PushTokenSyncResult.NoMonitorSession -> "not_monitor"
    PushTokenSyncResult.AuthUnavailable -> "auth_unavailable"
    PushTokenSyncResult.StorageUnavailable -> "storage_failure"
    PushTokenSyncResult.Revoked -> "revoked"
    PushTokenSyncResult.NotFound -> "not_found"
    is PushTokenSyncResult.RemoteFailure -> "remote_failure"
}

private fun PushTokenSyncResult.diagnosticCode(): String? =
    (this as? PushTokenSyncResult.RemoteFailure)?.errorCode
