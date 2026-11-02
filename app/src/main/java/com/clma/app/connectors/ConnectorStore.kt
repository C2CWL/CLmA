package com.clma.app.connectors

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Device-bound, non-exportable connector credentials and mail drafts. No plaintext fallback. */
class ConnectorStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("connectors_private", Context.MODE_PRIVATE)
    private val changed = MutableStateFlow(0L)
    val revision = changed.asStateFlow()

    fun get(name: String): String? {
        val value = prefs.getString(name, null) ?: return null
        return try {
            ConnectorCipher.decrypt(name, value, key())
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun put(name: String, value: String) {
        val encrypted = ConnectorCipher.encrypt(name, value, key())
        check(prefs.edit().putString(name, encrypted).commit()) { "connector_storage_unavailable" }
        changed.value += 1
    }

    @Synchronized
    fun remove(name: String) {
        check(prefs.edit().remove(name).commit()) { "connector_storage_unavailable" }
        changed.value += 1
    }

    fun keys(prefix: String): List<String> = prefs.all.keys.filter { it.startsWith(prefix) }.sorted()

    @Synchronized
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "clma_connector_credentials_v1"
    }
}
