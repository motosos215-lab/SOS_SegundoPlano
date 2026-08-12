package com.example.sos_segundoplano.data.remote.emergency

import android.content.Context
import com.example.sos_segundoplano.BuildConfig
import com.example.sos_segundoplano.core.auth.AuthProvider
import com.example.sos_segundoplano.data.remote.auth.AuthNetworkFactory
import com.example.sos_segundoplano.data.repository.DefaultEmergencyContactsRepository
import com.example.sos_segundoplano.domain.emergency.EmergencyContactsRepository

object EmergencyContactsProvider {
    @Volatile private var repository: EmergencyContactsRepository? = null
    fun get(context: Context): EmergencyContactsRepository = repository ?: synchronized(this) {
        repository ?: run {
            val moshi = AuthNetworkFactory.createMoshi()
            DefaultEmergencyContactsRepository(AuthProvider.get(context.applicationContext), RetrofitEmergencyContactsRemoteDataSource(AuthNetworkFactory.createEmergencyContactsApi(BuildConfig.MOTOSOS_API_BASE_URL, moshi), moshi))
                .also { repository = it }
        }
    }
}
