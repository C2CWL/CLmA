package me.rerere.rikkahub.workflow.secrets

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

private const val TAG = "WorkflowSecretsStore"

/**
 * T-07 — encrypted named secrets for workflow HTTP actions, addressed as `{{secret:NAME}}`.
 *
 * Why this exists: a workflow definition is stored as plain JSON in Room, is rendered back to
 * the LLM on every read, and is echoed into run history. An API token written inline in a
 * `web_fetch` header would therefore end up in three places the user never thinks of as
 * "where my token lives". Here the token lives in SharedPreferences, AES/GCM-encrypted under
 * an Android-Keystore master key, and the workflow only ever contains the *name*.
 *
 * Mirrors the storage discipline of `SkillSecretsStore` (same cipher, same fallback): the
 * master key is device-bound and non-exportable, and the prefs file is excluded from cloud
 * backup (see res/xml/backup_rules.xml) because a restored ciphertext is useless on a new
 * device — re-entering the token there is intentional.
 *
 * Deliberately scope-free: a secret is addressed by name across every workflow, so a user with
 * one weather API key stores it once. The narrow reading policy lives in `ActionTemplates` —
 * only `web_fetch` request headers may reference one.
 */
class WorkflowSecretsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** True when [name] is storable (1..64 of [A-Za-z0-9_]). */
    fun isValidName(name: String): Boolean = NAME_PATTERN.matches(name)

    /**
     * Store or replace [name]. Returns false when the name is not storable — the caller
     * surfaces that rather than writing an unaddressable entry.
     */
    fun set(name: String, value: String): Boolean {
        if (!isValidName(name)) return false
        val (encrypted, iv) = encrypt(value)
        prefs.edit {
            putString(secretKey(name), encrypted)
            putString(ivKey(name), iv)
        }
        return true
    }

    /** Decrypted value, or null when absent / undecryptable (keystore reset, bad ciphertext). */
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

    /** Every stored secret name, sorted. Values are never exposed by this call. */
    fun list(): List<String> =
        prefs.all.keys
            .filter { it.startsWith(SECRET_PREFIX) }
            .map { it.removePrefix(SECRET_PREFIX) }
            .filter { isValidName(it) }
            .sorted()

    /** True when a value is stored under [name]. Used to report a missing secret early. */
    fun has(name: String): Boolean = prefs.contains(secretKey(name))

    // -- crypto -------------------------------------------------------------

    private fun encrypt(plain: String): Pair<String, String> = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        // Read the IV before doFinal, matching SkillSecretsStore: GCM fixes the IV at init,
        // so either order works, but identical code is one less thing to misread.
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8))
        encode(encrypted) to encode(iv)
    } catch (t: Throwable) {
        // Same escape hatch as SkillSecretsStore: on devices where the keystore is
        // unavailable we still store the value (obfuscated) rather than breaking the user
        // flow, and we log loudly so the weaker storage is visible in logcat.
        Log.w(TAG, "Keystore-backed encrypt failed; falling back to obfuscated plaintext", t)
        encode(plain.toByteArray(StandardCharsets.UTF_8)) to FALLBACK_IV_MARKER
    }

    private fun decrypt(encrypted: String, iv: String): String? = try {
        if (iv == FALLBACK_IV_MARKER) {
            String(decode(encrypted), StandardCharsets.UTF_8)
        } else {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, decode(iv)))
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
                .build()
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
        const val PREFS_NAME = "workflow_secrets"

        private const val SECRET_PREFIX = "wf_secret_"
        private const val IV_PREFIX = "wf_iv_"
        private const val KEY_ALIAS = "workflow_secrets_master"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val FALLBACK_IV_MARKER = "plain"
        private val NAME_PATTERN = Regex("""[A-Za-z0-9_]{1,64}""")
    }
}
