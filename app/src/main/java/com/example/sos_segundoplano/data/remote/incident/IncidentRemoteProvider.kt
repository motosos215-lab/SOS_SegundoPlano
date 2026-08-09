package com.example.sos_segundoplano.data.remote.incident

import android.content.Context
import com.example.sos_segundoplano.BuildConfig
import com.example.sos_segundoplano.core.auth.AuthProvider
import com.example.sos_segundoplano.data.remote.auth.AuthNetworkFactory
import com.example.sos_segundoplano.data.validation.FalsePositiveValidationCoordinatorProvider

object IncidentRemoteProvider {
    @Volatile private var creator: IncidentRemoteCreator? = null

    fun initialize(context: Context): IncidentRemoteCreator = get(context).also { remoteCreator ->
        FalsePositiveValidationCoordinatorProvider.setIncidentRemoteCreator(remoteCreator)
    }

    fun get(context: Context): IncidentRemoteCreator = creator ?: synchronized(this) {
        creator ?: create(context.applicationContext).also { creator = it }
    }

    private fun create(context: Context): IncidentRemoteCreator {
        val moshi = AuthNetworkFactory.createMoshi()
        val api = AuthNetworkFactory.createIncidentsApi(BuildConfig.MOTOSOS_API_BASE_URL, moshi)
        return AuthenticatedIncidentRemoteCreator(
            authRepository = AuthProvider.get(context),
            remoteDataSource = RetrofitIncidentRemoteDataSource(api, moshi)
        )
    }
}
