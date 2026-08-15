package com.example.sos_segundoplano.data.repository

import android.content.Context
import com.example.sos_segundoplano.BuildConfig
import com.example.sos_segundoplano.core.auth.AuthProvider
import com.example.sos_segundoplano.data.remote.auth.AuthNetworkFactory
import com.example.sos_segundoplano.domain.history.RiderHistoryRepository

object RiderHistoryProvider {
    @Volatile private var repository: RiderHistoryRepository? = null

    fun get(context: Context): RiderHistoryRepository = repository ?: synchronized(this) {
        repository ?: AuthNetworkFactory.createMoshi().let { moshi ->
            DefaultRiderHistoryRepository(
                authRepository = AuthProvider.get(context.applicationContext),
                tripsApi = AuthNetworkFactory.createTripsApi(BuildConfig.MOTOSOS_API_BASE_URL, moshi),
                incidentsApi = AuthNetworkFactory.createIncidentsApi(BuildConfig.MOTOSOS_API_BASE_URL, moshi)
            ).also { repository = it }
        }
    }
}
