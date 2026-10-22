package com.clma.app.data.secrets

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.core.content.edit
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Agent-scoped encrypted secret vault.
 *
 * Why: passwords / tokens typed into chat or tool args are shipped to the LLM provider on
 * every subsequent turn. Here the value lives only on-device (AES/GCM under an Android
 * Keystore master key). The model sees the *name* (`webdav_pass`, `ssh_vps_key`, …) and
 * tools resolve it locally at execute time (`password_secret`, `stdin_secret`, …).
 *
 * Mirrors [com.clma.app.workflow.secrets.WorkflowSecretsStore] / SkillSecretsStore
 * storage discipline: device-bound key, prefs excluded from cloud backup by the
 * sharedpref deny-by-default rules, fallback obfuscation if the keystore is unavailable.
 */
class AgentSecretsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isValidName(name: String): Boolean = NAME_PATTERN.matches(name)

    fun set(name: String, value: String): Boolean {
        if (!isValidName(name)) return false
        val (encrypted, iv) = encrypt(value)
        prefs.edit {
            putString(secretKey(name), encrypted)
            putString(ivKey(name), iv)
        }
        return true
    }

    fun get(name: String): String? {
        val encrypted = prefs.getString(secretKey(name), null) ?: return null
        val iv = prefs.getString(ivKey(name), null) ?: return null
        return decrypt(encrypted, iv)
    }

    fun remove(name: String) {
        prefs.edit {
            remove(secretKey(name))
            remove(ivKey(name))
        }
    }

    /** Names only — values never leave this class via list. */
    fun list(): List<String> =
        prefs.all.keys
            .filter { it.startsWith(SECRET_PREFIX) }
            .map { it.removePrefix(SECRET_PREFIX) }
            .filter { isValidName(it) }
            .sorted()

    fun has(name: String): Boolean = prefs.contains(secretKey(name))

    private fun encrypt(plain: String): Pair<String, String> = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8))
        encode(encrypted) to encode(iv)
    } catch (t: Throwable) {
        Log.w(TAG, "Keystore-backed encrypt failed; falling back to obfuscated plaintext", t)
        encode(plain.toByteArray(StandardCharsets.UTF_8)) to FALLBACK_IV_MARKER
    }

    private fun decrypt(encrypted: String, iv: String): String? = try {
        if (iv == FALLBACK_IV_MARKER) {
            String(decode(encrypted), StandardCharsets.UTF_8)
        } else {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_BITS, decode(iv)),
            )
            String(cipher.doFinal(decode(encrypted)), StandardCharsets.UTF_8)
        }
    } catch (t: Throwable) {
        Log.w(TAG, "Decrypt failed", t)
        null
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encode(bytes: ByteArray): String =
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    private fun decode(value: String): ByteArray =
        android.util.Base64.decode(value, android.util.Base64.NO_WRAP)

    private fun secretKey(name: String): String = "$SECRET_PREFIX$name"
    private fun ivKey(name: String): String = "$IV_PREFIX$name"

    companion object {
        const val PREFS_NAME = "agent_secrets"

        private const val TAG = "AgentSecretsStore"
        private const val SECRET_PREFIX = "ag_secret_"
        private const val IV_PREFIX = "ag_iv_"
        private const val KEY_ALIAS = "agent_secrets_master"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val FALLBACK_IV_MARKER = "plain"
        private val NAME_PATTERN = Regex("""[A-Za-z0-9_]{1,64}""")
    }
}
