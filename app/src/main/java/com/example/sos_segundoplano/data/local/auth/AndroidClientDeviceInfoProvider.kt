package com.example.sos_segundoplano.data.local.auth

import android.content.Context
import android.os.Build
import com.example.sos_segundoplano.BuildConfig
import com.example.sos_segundoplano.domain.auth.ClientDeviceInfo
import java.util.UUID

fun interface ClientDeviceInfoProvider {
    fun current(): ClientDeviceInfo
}

class AndroidClientDeviceInfoProvider(context: Context) : ClientDeviceInfoProvider {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun current(): ClientDeviceInfo = ClientDeviceInfo(
        clientDeviceId = stableInstallationId(),
        deviceName = listOf(Build.MANUFACTURER, Build.MODEL)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(" ")
            .ifBlank { "Android" },
        platform = "Android",
        osVersion = "Android ${Build.VERSION.RELEASE}",
        appVersion = BuildConfig.VERSION_NAME
    )

    private fun stableInstallationId(): String = synchronized(preferences) {
        preferences.getString(KEY_CLIENT_DEVICE_ID, null)
            ?.takeIf(::isUuid)
            ?: UUID.randomUUID().toString().also { generated ->
                check(preferences.edit().putString(KEY_CLIENT_DEVICE_ID, generated).commit()) {
                    "client_device_id_persistence_failed"
                }
            }
    }

    private fun isUuid(value: String): Boolean = runCatching { UUID.fromString(value) }.isSuccess

    private companion object {
        const val PREFERENCES_NAME = "motosos_client_installation"
        const val KEY_CLIENT_DEVICE_ID = "client_device_id"
    }
}
