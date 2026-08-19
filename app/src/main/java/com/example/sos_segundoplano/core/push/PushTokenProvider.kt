package com.example.sos_segundoplano.core.push

import android.content.Context
import com.example.sos_segundoplano.core.auth.AuthProvider
import com.example.sos_segundoplano.data.local.push.KeystoreEncryptedPushTokenStore
import com.example.sos_segundoplano.domain.push.PushTokenCoordinator
import com.example.sos_segundoplano.domain.push.PushTokenStoreResult
import com.example.sos_segundoplano.push.PushDiagnostics
import com.google.firebase.messaging.FirebaseMessaging

fun interface InitialPushTokenFetcher {
    fun fetch(onToken: (String) -> Unit)
}

class FirebaseInitialPushTokenFetcher : InitialPushTokenFetcher {
    override fun fetch(onToken: (String) -> Unit) {
        PushDiagnostics.debug(PushDiagnostics.initialFetchStarted())
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            PushDiagnostics.debug(PushDiagnostics.initialFetchResult(success = token.isNotBlank()))
            if (token.isNotBlank()) onToken(token)
        }.addOnFailureListener { error ->
            PushDiagnostics.debug(PushDiagnostics.initialFetchResult(false, error))
        }
    }
}

class PushTokenBootstrap(
    private val coordinator: PushTokenCoordinator,
    private val fetcher: InitialPushTokenFetcher,
    private val onTokenRecorded: () -> Unit = {}
) {
    fun start() {
        runCatching {
            fetcher.fetch { token ->
                if (coordinator.recordToken(token) is PushTokenStoreResult.Success) {
                    onTokenRecorded()
                }
            }
        }
    }
}

object PushTokenProvider {
    @Volatile private var coordinator: PushTokenCoordinator? = null

    fun initialize(context: Context) {
        val installed = get(context)
        PushTokenRegistrationProvider.initialize(
            authRepository = AuthProvider.get(context.applicationContext),
            store = installed.store()
        )
        PushTokenBootstrap(
            installed,
            FirebaseInitialPushTokenFetcher(),
            PushTokenRegistrationProvider::scheduleSync
        ).start()
    }

    fun get(context: Context): PushTokenCoordinator = coordinator ?: synchronized(this) {
        coordinator ?: PushTokenCoordinator(
            KeystoreEncryptedPushTokenStore(context.applicationContext)
        ).also { coordinator = it }
    }
}
