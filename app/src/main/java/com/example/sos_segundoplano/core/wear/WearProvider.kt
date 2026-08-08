package com.example.sos_segundoplano.core.wear

import android.content.Context
import com.example.sos_segundoplano.core.auth.AuthProvider
import com.example.sos_segundoplano.data.wear.DefaultWatchConnectionRepository
import com.example.sos_segundoplano.data.wear.GooglePlayWearGateway
import com.example.sos_segundoplano.domain.wear.WatchConnectionRepository

/**
 * Provee el repositorio de conexión con el reloj Wear. Sigue el patrón de [AuthProvider] y
 * [ProfileProvider]: singleton perezoso y seguro entre hilos con `applicationContext`.
 */
object WearProvider {
    @Volatile
    private var repository: WatchConnectionRepository? = null

    fun get(context: Context): WatchConnectionRepository = repository ?: synchronized(this) {
        repository ?: create(context.applicationContext).also { repository = it }
    }

    private fun create(context: Context): WatchConnectionRepository =
        DefaultWatchConnectionRepository(
            authRepository = AuthProvider.get(context),
            gateway = GooglePlayWearGateway(context)
        )
}