package com.example.sos_segundoplano.data.remote.push

import android.content.Context
import com.example.sos_segundoplano.BuildConfig
import com.example.sos_segundoplano.core.auth.AuthProvider
import com.example.sos_segundoplano.data.remote.auth.AuthNetworkFactory
import com.example.sos_segundoplano.data.repository.DefaultMonitorPushTokenStatusRepository
import com.example.sos_segundoplano.domain.push.MonitorPushTokenStatusRepository

object MonitorPushTokenStatusProvider {
    @Volatile private var repository: MonitorPushTokenStatusRepository? = null

    fun get(context: Context): MonitorPushTokenStatusRepository = repository ?: synchronized(this) {
        repository ?: run {
            val appContext = context.applicationContext
            val moshi = AuthNetworkFactory.createMoshi()
            val api = AuthNetworkFactory.createPushNotificationTokensApi(BuildConfig.MOTOSOS_API_BASE_URL, moshi)
            DefaultMonitorPushTokenStatusRepository(
                authRepository = AuthProvider.get(appContext),
                remote = RetrofitPushTokenRemoteDataSource(api, moshi)
            ).also { repository = it }
        }
    }
}
