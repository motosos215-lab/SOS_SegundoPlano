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
import com.example.sos_segundoplano.domain.repository.AuthRepository
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
        applicationScope.launch { installed.syncPendingMonitorToken() }
    }

    suspend fun revokeBeforeLogout(): PushTokenSyncResult =
        repository?.revokeMonitorRegistration() ?: PushTokenSyncResult.NothingPending
}
