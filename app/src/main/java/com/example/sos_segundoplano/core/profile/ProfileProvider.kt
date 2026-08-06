package com.example.sos_segundoplano.core.profile

import android.content.Context
import com.example.sos_segundoplano.BuildConfig
import com.example.sos_segundoplano.core.auth.AuthProvider
import com.example.sos_segundoplano.data.remote.auth.AuthNetworkFactory
import com.example.sos_segundoplano.data.remote.profile.RetrofitProfileRemoteDataSource
import com.example.sos_segundoplano.data.repository.DefaultProfileRepository
import com.example.sos_segundoplano.domain.repository.ProfileRepository

object ProfileProvider {
    @Volatile
    private var repository: ProfileRepository? = null

    fun get(context: Context): ProfileRepository = repository ?: synchronized(this) {
        repository ?: create(context.applicationContext).also { repository = it }
    }

    private fun create(context: Context): ProfileRepository {
        val moshi = AuthNetworkFactory.createMoshi()
        val api = AuthNetworkFactory.createUsersApi(BuildConfig.MOTOSOS_API_BASE_URL, moshi)
        return DefaultProfileRepository(
            remoteDataSource = RetrofitProfileRemoteDataSource(api, moshi),
            authRepository = AuthProvider.get(context)
        )
    }
}
