package com.example.sos_segundoplano.data.offline

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class AndroidKeystoreAesGcmCrypto(
    keyVersion: Int = KEY_VERSION,
    keyAlias: String = productionAlias(keyVersion)
) : AesGcmOfflineQueueCrypto(
    keyVersion = keyVersion,
    secretKeyProvider = lazyKeyProvider(keyAlias)
) {
    companion object {
        const val KEY_VERSION = 1
        const val KEY_ALIAS = "motosos_offline_queue_aes_gcm_v1"

        fun productionAlias(keyVersion: Int): String = if (keyVersion == KEY_VERSION) KEY_ALIAS else "motosos_offline_queue_aes_gcm_v$keyVersion"

        private fun lazyKeyProvider(alias: String): () -> SecretKey {
            val lazyKey = lazy(LazyThreadSafetyMode.SYNCHRONIZED) { getOrCreateKey(alias) }
            return { lazyKey.value }
        }

        private fun getOrCreateKey(alias: String): SecretKey {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            keyGenerator.init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            return keyGenerator.generateKey()
        }
    }
}
