package com.clma.app.data.secrets

import android.util.Base64

/**
 * Value-based redactor.
 *
 * [com.clma.ai.util.redactSecrets] only blanks values whose *JSON key* looks secret
 * (`password`, `token`, …). That leaves a real hole: the vault resolves `stdin_secret` /
 * `password_secret` to a plaintext value at tool-execute time, and if the remote side (or a
 * `set -x`, an error trace, a `curl -v`) echoes that value back in stdout/stderr, the text
 * arrives under an innocent key and is stored in history and shipped to the model verbatim.
 *
 * This redactor works on *content*: it is handed the vault snapshot and replaces every
 * occurrence of every value — raw, Base64-encoded, and URL-encoded — with a stable marker
 * naming the secret, so the model can still say "the token you gave me is wrong" without
 * ever seeing it.
 *
 * Deliberately pure (no Android calls beyond Base64) so JVM tests drive the real code path.
 */
object SecretValueRedactor {

    /** Values shorter than this are skipped: they would blank ordinary prose. */
    const val MIN_VALUE_LENGTH = 6

    fun redact(text: String, secrets: Map<String, String>): String {
        if (text.isEmpty() || secrets.isEmpty()) return text
        var out = text
        for ((name, value) in secrets) {
            if (value.length < MIN_VALUE_LENGTH) continue
            val marker = "[REDACTED:$name]"
            // Longest form first: a Base64/URL-encoded variant contains the raw value only
            // by accident, but ordering keeps the markers consistent when both appear.
            variantsOf(value).forEach { variant ->
                if (variant.length >= MIN_VALUE_LENGTH && out.contains(variant)) {
                    out = out.replace(variant, marker)
                }
            }
        }
        return out
    }

    /**
     * The forms a secret realistically shows up as in command output:
     * raw, Base64 (JWT/basic-auth headers, `base64 <<< "$pat"`), and URL-encoded
     * (a token pasted into a query string).
     */
    fun variantsOf(value: String): List<String> = buildList {
        add(value)
        runCatching {
            add(Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
        }
        add(urlEncode(value))
    }.filter { it.isNotBlank() }.distinct()

    private fun urlEncode(value: String): String = buildString {
        value.toByteArray(Charsets.UTF_8).forEach { byte ->
            val c = byte.toInt() and 0xFF
            val ch = c.toChar()
            if (ch.isLetterOrDigit() && c < 128 || ch in "-._~") {
                append(ch)
            } else {
                append('%').append(HEX[c shr 4]).append(HEX[c and 0x0F])
            }
        }
    }

    private const val HEX = "0123456789ABCDEF"
}

/**
 * Process-wide handle on the agent vault, so the generation pipeline can redact its own
 * output without taking an [AgentSecretsStore] constructor dependency (that would ripple
 * through GenerationLoop → ChatService → every test fake).
 *
 * Installed once by DI; absent in tests, where [redact] becomes the identity function.
 */
object SecretVaultRegistry {

    @Volatile
    private var store: AgentSecretsStore? = null

    fun install(store: AgentSecretsStore) {
        this.store = store
    }

    /** Name → plaintext for every readable entry. Never logged, never persisted. */
    fun snapshotValues(): Map<String, String> {
        val vault = store ?: return emptyMap()
        return buildMap {
            vault.list().forEach { name ->
                val value = runCatching { vault.get(name) }.getOrNull()
                if (!value.isNullOrBlank()) put(name, value)
            }
        }
    }

    fun redact(text: String): String {
        val secrets = snapshotValues()
        if (secrets.isEmpty()) return text
        return SecretValueRedactor.redact(text, secrets)
    }
}