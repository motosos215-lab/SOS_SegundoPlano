package com.example.sos_segundoplano.data.local.auth

import android.content.Context
import java.security.MessageDigest

class LinkedMobileDeviceIdStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun saveForAccount(email: String, mobileDeviceId: String) {
        val normalizedId = mobileDeviceId.trim()
        if (normalizedId.isEmpty()) return
        preferences.edit().putString(key(email), normalizedId).apply()
    }

    fun readForAccount(email: String): String? = preferences.getString(key(email), null)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    private fun key(email: String): String {
        val normalized = email.trim().lowercase()
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
        return "mobile_" + digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private companion object {
        const val PREFERENCES_NAME = "motosos_linked_mobile_device"
    }
}
