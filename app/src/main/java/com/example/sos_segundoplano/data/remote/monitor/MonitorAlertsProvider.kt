package com.example.sos_segundoplano.data.remote.monitor

import android.content.Context
import com.example.sos_segundoplano.BuildConfig
import com.example.sos_segundoplano.core.auth.AuthProvider
import com.example.sos_segundoplano.data.remote.auth.AuthNetworkFactory
import com.example.sos_segundoplano.data.repository.DefaultMonitorAlertsRepository
import com.example.sos_segundoplano.domain.monitor.MonitorAlertsRepository

object MonitorAlertsProvider {
    @Volatile private var repository: MonitorAlertsRepository? = null
    fun get(context: Context): MonitorAlertsRepository = repository ?: synchronized(this) {
        repository ?: run {
            val moshi = AuthNetworkFactory.createMoshi()
            DefaultMonitorAlertsRepository(AuthProvider.get(context.applicationContext), RetrofitMonitorAlertsRemoteDataSource(AuthNetworkFactory.createMonitorAlertsApi(BuildConfig.MOTOSOS_API_BASE_URL, moshi), moshi))
                .also { repository = it }
        }
    }
}
