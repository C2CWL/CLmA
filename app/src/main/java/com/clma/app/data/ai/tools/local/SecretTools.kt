package com.clma.app.data.ai.tools.local

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.clma.ai.core.InputSchema
import com.clma.ai.core.Tool
import com.clma.ai.ui.UIMessagePart
import com.clma.app.data.secrets.AgentSecretsStore

/**
 * Agent secret vault tools.
 *
 * `request_secret` never receives the plaintext: it pauses like `ask_user`, the in-app
 * secure-input card writes the value into [AgentSecretsStore], and the tool result only
 * reports `{stored:true, name}`. Other tools then reference the name via `*_secret` fields
 * (e.g. SSH `password_secret` / `stdin_secret`) which are resolved locally at execute time.
 */

const val REQUEST_SECRET_TOOL_NAME = "request_secret"

fun requestSecretTool(): Tool = Tool(
    name = REQUEST_SECRET_TOOL_NAME,
    description = """
        Request that the user enter a secret (password, API token, private key, etc.) into a
        local secure input card. The plaintext NEVER enters the conversation or this tool's
        result — only a name is stored in the on-device vault. Afterwards reference it as
        password_secret / private_key_secret / passphrase_secret / stdin_secret on SSH tools
        (and similar *_secret fields elsewhere). Do NOT ask the user to type secrets in chat
        and do NOT put plaintext passwords in tool arguments.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "Vault key: 1..64 of [A-Za-z0-9_], e.g. webdav_pass or ssh_vps")
                })
                put("prompt", buildJsonObject {
                    put("type", "string")
                    put("description", "Short label shown on the secure input card")
                })
                put("kind", buildJsonObject {
                    put("type", "string")
                    put(
                        "enum",
                        buildJsonArray {
                            add("password")
                            add("private_key")
                            add("text")
                        },
                    )
                    put(
                        "description",
                        "password (default, masked single-line), private_key (multiline PEM), text (masked generic)",
                    )
                })
            },
            required = listOf("name"),
        )
    },
    needsApproval = { input ->
        val name = runCatching { input.jsonObject["name"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        !name.isNullOrBlank() && Regex("[A-Za-z0-9_]{1,64}").matches(name)
    },
    execute = { input ->
        val name = runCatching { input.jsonObject["name"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        if (name.isNullOrBlank() || !Regex("[A-Za-z0-9_]{1,64}").matches(name)) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "invalid_tool_args")
                        put(
                            "detail",
                            "Invalid request_secret call: 'name' is required and must match [A-Za-z0-9_]{1,64}.",
                        )
                    }.toString(),
                ),
            )
        }
        // Reached only when no interactive surface handled the call (cron / sub-agent).
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("error", "request_secret_unavailable")
                    put(
                        "detail",
                        "Secure input is not available in this context. Ask the user (in the main chat) " +
                            "to store the secret via Settings or a foreground session, then retry with the vault name.",
                    )
                }.toString(),
            ),
        )
    },
)

fun secretListTool(store: AgentSecretsStore): Tool = Tool(
    name = "secret_list",
    description = "List names of secrets in the on-device agent vault. Values are never returned.",
    parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
    execute = {
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("secrets", buildJsonArray {
                        store.list().forEach { add(it) }
                    })
                }.toString(),
            ),
        )
    },
)

fun secretDeleteTool(store: AgentSecretsStore): Tool = Tool(
    name = "secret_delete",
    description = "Delete a secret from the on-device agent vault by name. Irreversible.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "Vault key to delete")
                })
            },
            required = listOf("name"),
        )
    },
    execute = { input ->
        val name = input.jsonObject["name"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool listOf(
                UIMessagePart.Text(buildJsonObject { put("error", "name is required") }.toString()),
            )
        if (!store.isValidName(name)) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "invalid_name")
                        put("detail", "name must match [A-Za-z0-9_]{1,64}")
                    }.toString(),
                ),
            )
        }
        if (!store.has(name)) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "not_found")
                        put("name", name)
                    }.toString(),
                ),
            )
        }
        store.remove(name)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("success", true)
                    put("name", name)
                }.toString(),
            ),
        )
    },
)

/**
 * Resolve a vault reference. Returns null when [refName] is null/blank.
 * Returns Result.failure when the name is set but missing/undecryptable.
 */
fun resolveSecretRef(store: AgentSecretsStore, refName: String?): Result<String?> {
    if (refName.isNullOrBlank()) return Result.success(null)
    if (!store.isValidName(refName)) {
        return Result.failure(IllegalArgumentException("invalid secret name '$refName'"))
    }
    val value = store.get(refName)
        ?: return Result.failure(IllegalStateException("secret_not_found: '$refName' is not in the vault — call request_secret first"))
    return Result.success(value)
}
