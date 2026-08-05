package com.example.sos_segundoplano.core.auth

import android.content.Context
import com.example.sos_segundoplano.BuildConfig
import com.example.sos_segundoplano.data.local.auth.KeystoreEncryptedSessionStore
import com.example.sos_segundoplano.data.remote.auth.AuthNetworkFactory
import com.example.sos_segundoplano.data.remote.auth.RetrofitAuthRemoteDataSource
import com.example.sos_segundoplano.data.repository.DefaultAuthRepository
import com.example.sos_segundoplano.domain.auth.SystemAuthClock
import com.example.sos_segundoplano.domain.repository.AuthRepository
import com.example.sos_segundoplano.domain.auth.AuthResult
import com.example.sos_segundoplano.domain.auth.AuthUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.util.concurrent.atomic.AtomicBoolean

object AuthProvider {
    @Volatile
    private var repository: AuthRepository? = null
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val restorationStarted = AtomicBoolean(false)
    @Volatile
    private var restoration: Deferred<AuthResult<AuthUser?>>? = null

    fun initialize(context: Context): AuthRepository = get(context).also { authRepository ->
        if (restorationStarted.compareAndSet(false, true)) {
            restoration = applicationScope.async { authRepository.restoreSession() }
        }
    }

    fun restorationResult(): Deferred<AuthResult<AuthUser?>>? = restoration

    fun get(context: Context): AuthRepository = repository ?: synchronized(this) {
        repository ?: create(context.applicationContext).also { repository = it }
    }

    private fun create(context: Context): AuthRepository {
        val moshi = AuthNetworkFactory.createMoshi()
        val api = AuthNetworkFactory.createApi(BuildConfig.MOTOSOS_API_BASE_URL, moshi)
        return DefaultAuthRepository(
            remoteDataSource = RetrofitAuthRemoteDataSource(api, moshi),
            sessionStore = KeystoreEncryptedSessionStore(context, moshi),
            clock = SystemAuthClock
        )
    }
}
