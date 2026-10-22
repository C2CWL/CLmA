package com.clma.app.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.clma.ai.core.InputSchema
import com.clma.ai.core.Tool
import com.clma.ai.ui.UIMessagePart
import com.clma.app.data.db.entity.SshHostEntity
import com.clma.app.data.repository.SshHostRepository
import com.clma.app.data.secrets.AgentSecretsStore

/**
 * Save an SSH host so it can be referenced by name in subsequent calls instead of passing
 * credentials every time. Replaces any existing host with the same name.
 *
 * Prefer `password_secret` / `private_key_secret` / `passphrase_secret` (vault names from
 * `request_secret`) so plaintext never enters chat history or the LLM cloud. Raw
 * password/private_key still work for backwards compatibility but are redacted on persist
 * and when re-sent to the model.
 */
fun saveSshHostTool(
    repo: SshHostRepository,
    secrets: AgentSecretsStore,
): Tool = Tool(
    name = "save_ssh_host",
    description = """
        Persist an SSH host (host, port, user, credentials) under a short name so the LLM can
        reference it later via ssh_exec_saved / ssh_upload / ssh_download without re-typing
        credentials. Replaces any existing host with the same name. Prefer password_secret /
        private_key_secret (vault names from request_secret) over plaintext password /
        private_key so secrets never enter chat history. Pass either password(+_secret) OR
        private_key(+_secret) for authentication.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("name", buildJsonObject { put("type", "string"); put("description", "Short name (used as lookup key)") })
                put("host", buildJsonObject { put("type", "string"); put("description", "Hostname or IP") })
                put("port", buildJsonObject { put("type", "integer"); put("description", "SSH port, default 22") })
                put("user", buildJsonObject { put("type", "string"); put("description", "SSH username") })
                put("password_secret", buildJsonObject {
                    put("type", "string")
                    put("description", "Vault name for the password (from request_secret). Preferred over password.")
                })
                put("private_key_secret", buildJsonObject {
                    put("type", "string")
                    put("description", "Vault name for the private key PEM (from request_secret). Preferred over private_key.")
                })
                put("passphrase_secret", buildJsonObject {
                    put("type", "string")
                    put("description", "Vault name for the key passphrase (from request_secret). Preferred over passphrase.")
                })
                put("password", buildJsonObject {
                    put("type", "string")
                    put("description", "DEPRECATED plaintext password — use password_secret + request_secret instead")
                })
                put("private_key", buildJsonObject {
                    put("type", "string")
                    put("description", "DEPRECATED plaintext PEM — use private_key_secret + request_secret instead")
                })
                put("passphrase", buildJsonObject {
                    put("type", "string")
                    put("description", "DEPRECATED plaintext passphrase — use passphrase_secret instead")
                })
            },
            required = listOf("name", "host", "user"),
        )
    },
    execute = { input ->
        val p = input.jsonObject
        val name = p["name"]?.jsonPrimitive?.contentOrNull ?: error("name is required")
        val host = p["host"]?.jsonPrimitive?.contentOrNull ?: error("host is required")
        val user = p["user"]?.jsonPrimitive?.contentOrNull ?: error("user is required")
        val port = p["port"]?.jsonPrimitive?.intOrNull ?: 22

        val password = resolveSecretOrPlain(
            secrets,
            refName = p["password_secret"]?.jsonPrimitive?.contentOrNull,
            plain = p["password"]?.jsonPrimitive?.contentOrNull,
            field = "password",
        ).getOrElse { return@Tool secretError(it) }
        val privateKey = resolveSecretOrPlain(
            secrets,
            refName = p["private_key_secret"]?.jsonPrimitive?.contentOrNull,
            plain = p["private_key"]?.jsonPrimitive?.contentOrNull,
            field = "private_key",
        ).getOrElse { return@Tool secretError(it) }
        val passphrase = resolveSecretOrPlain(
            secrets,
            refName = p["passphrase_secret"]?.jsonPrimitive?.contentOrNull,
            plain = p["passphrase"]?.jsonPrimitive?.contentOrNull,
            field = "passphrase",
        ).getOrElse { return@Tool secretError(it) }

        if (password.isNullOrBlank() && privateKey.isNullOrBlank()) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "missing_credentials")
                        put(
                            "detail",
                            "Provide password_secret or private_key_secret (call request_secret first), " +
                                "or legacy password/private_key. Prefer vault refs so secrets never enter chat.",
                        )
                    }.toString(),
                ),
            )
        }
        repo.upsert(
            SshHostEntity(
                name = name,
                host = host,
                port = port,
                user = user,
                password = password,
                privateKey = privateKey,
                passphrase = passphrase,
                createdAtMs = System.currentTimeMillis(),
            ),
        )
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("success", true)
                    put("name", name)
                    put("used_password_secret", !p["password_secret"]?.jsonPrimitive?.contentOrNull.isNullOrBlank())
                    put("used_private_key_secret", !p["private_key_secret"]?.jsonPrimitive?.contentOrNull.isNullOrBlank())
                }.toString(),
            ),
        )
    },
)

/** List saved hosts. Secrets omitted — only metadata + which auth method is configured. */
fun listSshHostsTool(repo: SshHostRepository): Tool = Tool(
    name = "list_ssh_hosts",
    description = "List all saved SSH hosts (name, host, port, user, has_password, has_private_key). Secrets are not returned.".trimIndent().replace("\n", " "),
    parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
    execute = {
        val all = repo.getAll()
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put(
                        "hosts",
                        buildJsonArray {
                            all.forEach { h ->
                                addJsonObject {
                                    put("name", h.name)
                                    put("host", h.host)
                                    put("port", h.port)
                                    put("user", h.user)
                                    put("has_password", !h.password.isNullOrBlank())
                                    put("has_private_key", !h.privateKey.isNullOrBlank())
                                }
                            }
                        },
                    )
                }.toString(),
            ),
        )
    },
)

/** Delete a saved host by name. */
fun deleteSshHostTool(repo: SshHostRepository): Tool = Tool(
    name = "delete_ssh_host",
    description = "Delete a saved SSH host by name.".trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("name", buildJsonObject { put("type", "string"); put("description", "Saved host name") })
            },
            required = listOf("name"),
        )
    },
    execute = { input ->
        val name = input.jsonObject["name"]?.jsonPrimitive?.contentOrNull
            ?: error("name is required")
        repo.deleteByName(name)
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
 * Forget the stored SSH host key(s) for a host. Use after the user explicitly confirms they
 * reinstalled the remote — the next connect will trust the new key per accept-new policy.
 */
fun forgetSshHostKeyTool(context: Context): Tool = Tool(
    name = "ssh_forget_host_key",
    description = """
        Remove the stored host key for an SSH host from known_hosts. Call this AFTER the user
        explicitly confirms they reinstalled the remote machine — the next connect will trust
        the new key. NEVER call this without user confirmation: a changed host key can also
        indicate a man-in-the-middle attack.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("host", buildJsonObject { put("type", "string"); put("description", "Hostname or IP whose stored key should be forgotten") })
            },
            required = listOf("host"),
        )
    },
    execute = { input ->
        val host = input.jsonObject["host"]?.jsonPrimitive?.contentOrNull
            ?: error("host is required")
        val removed = forgetHostKey(context, host)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("success", true)
                    put("host", host)
                    put("keys_removed", removed)
                }.toString(),
            ),
        )
    },
)

/** Run a command on a saved host without re-passing credentials. */
fun sshExecSavedTool(
    context: Context,
    repo: SshHostRepository,
    secrets: AgentSecretsStore,
): Tool = Tool(
    name = "ssh_exec_saved",
    description = """
        Run a shell command on a previously-saved SSH host (looked up by name). Returns
        stdout, stderr, exit_code. For secrets on stdin (sudo password, etc.) use
        stdin_secret with a vault name from request_secret — never put the password in stdin.
        For destructive or system-level commands you should explicitly confirm with the user
        before invoking.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("name", buildJsonObject { put("type", "string"); put("description", "Saved host name") })
                put("command", buildJsonObject { put("type", "string"); put("description", "Shell command to run") })
                put("stdin_secret", buildJsonObject {
                    put("type", "string")
                    put("description", "Vault name whose value is piped to stdin (preferred for passwords). Mutually exclusive with background.")
                })
                put("stdin", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional non-secret data piped to stdin. Prefer stdin_secret for passwords/tokens.")
                })
                put("background", buildJsonObject {
                    put("type", "boolean")
                    put("description", "If true, launch detached (nohup). Default false. Incompatible with stdin/stdin_secret.")
                })
                put("timeout_seconds", buildJsonObject {
                    put("type", "integer")
                    put("description", "Total timeout, default 30, max 300")
                })
            },
            required = listOf("name", "command"),
        )
    },
    execute = { input ->
        val p = input.jsonObject
        val name = p["name"]?.jsonPrimitive?.contentOrNull ?: error("name is required")
        val command = p["command"]?.jsonPrimitive?.contentOrNull ?: error("command is required")
        val stdin = resolveSecretOrPlain(
            secrets,
            refName = p["stdin_secret"]?.jsonPrimitive?.contentOrNull,
            plain = p["stdin"]?.jsonPrimitive?.contentOrNull,
            field = "stdin",
        ).getOrElse { return@Tool secretError(it) }
        val background = p["background"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val timeoutSec = (p["timeout_seconds"]?.jsonPrimitive?.intOrNull ?: 30).coerceIn(1, 300)
        val h = repo.getByName(name)
            ?: return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", "no saved host: $name") }.toString(),
                ),
            )
        val auth = SshAuth(password = h.password, privateKey = h.privateKey, passphrase = h.passphrase)
        if (!auth.isUsable()) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", "saved host has no usable credentials") }.toString(),
                ),
            )
        }
        if (background && stdin != null) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "stdin and background are mutually exclusive (a detached command reads from /dev/null)")
                    }.toString(),
                ),
            )
        }
        val effectiveCommand = if (background) wrapDetachedCommand(command) else command
        val payload = runCancellableSshOp(timeoutSec * 1000L) { sessionRef ->
            execOneShot(context, h.host, h.port, h.user, auth, effectiveCommand, timeoutSec * 1000, sessionRef, stdin)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    },
)

/**
 * Prefer vault ref when present; otherwise use plaintext. Vault wins if both are set so a
 * model that still echoes a placeholder password cannot override a real secret ref.
 */
internal fun resolveSecretOrPlain(
    secrets: AgentSecretsStore,
    refName: String?,
    plain: String?,
    field: String,
): Result<String?> {
    if (!refName.isNullOrBlank()) {
        return resolveSecretRef(secrets, refName).map { it }
    }
    return Result.success(plain)
}

internal fun secretError(t: Throwable): List<UIMessagePart> = listOf(
    UIMessagePart.Text(
        buildJsonObject {
            put("error", t.message ?: "secret_resolve_failed")
            put(
                "detail",
                "Call request_secret to store the value on-device, then pass the vault name via *_secret fields.",
            )
        }.toString(),
    ),
)
