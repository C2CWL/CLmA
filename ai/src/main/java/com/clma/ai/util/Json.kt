package com.clma.ai.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

@PublishedApi
internal val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/**
 * Keys whose string values are sensitive enough that the raw value MUST NOT land in
 * logcat or be re-sent to the LLM cloud: tool args/results and full request bodies land
 * in `Log.i` for debugging, and without redaction things like a `save_ssh_host` call's
 * `private_key` / `password` or a `telegram_set_token` call's `token` would print
 * verbatim — readable by other apps holding READ_LOGS on OEM-bugged ROMs, and by
 * `bugreport`/`dumpsys`. The match is case-insensitive against the key name and applies
 * regardless of nesting depth.
 *
 * Vault *reference* keys (`password_secret`, `private_key_secret`, `passphrase_secret`,
 * `stdin_secret`) hold only the on-device vault name, never the plaintext — they must
 * stay visible so the model can reuse the name on later turns.
 */
private val SECRET_KEY_PATTERN: Regex =
    Regex("(?:^|_)(password|passphrase|secret|token|apikey|api[_-]?key|privatekey|private[_-]?key|key)$",
        RegexOption.IGNORE_CASE)

/** Keys that name a vault entry rather than carrying the secret value itself. */
private val VAULT_REF_KEY_PATTERN: Regex =
    Regex("(?:password|private[_-]?key|passphrase|stdin)_secret$", RegexOption.IGNORE_CASE)

private fun isSecretKey(key: String): Boolean =
    SECRET_KEY_PATTERN.containsMatchIn(key) && !VAULT_REF_KEY_PATTERN.containsMatchIn(key)

/**
 * Walk [element] and replace any string primitive whose KEY matches [SECRET_KEY_PATTERN]
 * (and is not a vault-ref key) with the string `"***"`. Numbers, booleans, nulls, and
 * non-secret strings pass through unchanged.
 */
fun redactSecrets(element: JsonElement, key: String? = null): JsonElement {
    val isSecret = key != null && isSecretKey(key)
    return when (element) {
        is JsonPrimitive ->
            if (isSecret && element.isString) JsonPrimitive("***") else element
        is JsonObject -> JsonObject(element.mapValues { (k, v) -> redactSecrets(v, k) })
        is JsonArray -> buildJsonArray { element.forEach { add(redactSecrets(it, key)) } }
    }
}
