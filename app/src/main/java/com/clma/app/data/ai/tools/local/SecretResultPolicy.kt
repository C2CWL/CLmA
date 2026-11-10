package com.clma.app.data.ai.tools.local

import com.clma.app.data.secrets.AgentSecretsStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal fun resolveSecretOrPlain(
    secrets: AgentSecretsStore,
    refName: String?,
    plain: String?,
    field: String,
): Result<String?> = resolveSecretInput(refName, plain, field) { resolveSecretRef(secrets, it) }

/** Plain stdin is intentional. A supplied reference must never fall back to plaintext. */
internal fun resolveSecretInput(
    refName: String?,
    plain: String?,
    field: String,
    resolve: (String) -> Result<String?>,
): Result<String?> {
    if (field != "stdin" && plain != null) {
        return Result.failure(IllegalArgumentException("plaintext_credentials_rejected: use ${field}_secret"))
    }
    if (refName != null) {
        if (refName.isBlank()) return Result.failure(IllegalArgumentException("empty_secret_reference"))
        if (plain != null) return Result.failure(IllegalArgumentException("conflicting_secret_and_plain_input"))
        return resolve(refName)
    }
    return Result.success(plain)
}

internal fun validateSecretCapture(
    name: String?,
    background: Boolean,
    isValidName: (String) -> Boolean,
    exists: (String) -> Boolean,
): JsonObject? {
    if (name == null) return null
    val error = when {
        !isValidName(name) -> "invalid_output_secret_name"
        background -> "secret_capture_requires_foreground_command"
        exists(name) -> "secret_already_exists_choose_new_name"
        else -> return null
    }
    return buildJsonObject { put("error", error) }
}

/** Capture mode suppresses the ENTIRE payload, not just stdout, on every error path. */
internal fun captureSecretOutput(
    payload: JsonObject,
    name: String?,
    store: (String, String) -> Boolean,
): JsonObject {
    if (name == null) return payload
    val value = payload["stdout"]?.jsonPrimitive?.contentOrNull
    val complete = payload["exit_code"]?.jsonPrimitive?.intOrNull == 0 &&
        payload["error"] == null && payload["stdout_truncated"]?.jsonPrimitive?.booleanOrNull != true
    if (!complete || value.isNullOrEmpty()) {
        return buildJsonObject {
            put("error", "secret_capture_failed")
            put("detail", "Command failed, timed out, or stdout was empty/truncated. All output withheld; nothing stored. Do not retry in plaintext mode.")
        }
    }
    val saved = runCatching { store(name, value) }.getOrDefault(false)
    return buildJsonObject {
        if (saved) {
            put("stored", true)
            put("secret_name", name)
            put("detail", "Stored locally. Use secret_show for authenticated local viewing; use *_secret to reference it. Plaintext is not returned.")
        } else {
            put("error", "secret_store_failed")
            put("detail", "Secure storage failed. All output withheld; do not retry in plaintext mode.")
        }
    }
}
