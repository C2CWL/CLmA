package com.clma.app.data.ai.tools.local

import android.content.Context
import android.util.Log
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.clma.ai.core.InputSchema
import com.clma.ai.core.Tool
import com.clma.ai.ui.UIMessagePart
import com.clma.app.data.repository.SshHostRepository
import com.clma.app.data.repository.WorkspaceRepository
import java.io.File
import java.util.concurrent.atomic.AtomicReference

private const val TAG_SFTP = "SshSftpTool"

private fun openSftp(session: Session): ChannelSftp {
    val channel = session.openChannel("sftp") as ChannelSftp
    channel.connect()
    return channel
}

/**
 * Open a connected SSH session to a saved host with the same probe → bind → cancel logic
 * SSH exec uses. Stashes the live Session in [sessionRef] so the outer cancellable wrapper
 * can disconnect it on coroutine cancellation. Returns the [block]'s envelope, or a
 * connect-error envelope if the handshake failed before [block] could run.
 */
private suspend fun withSavedHostSession(
    context: Context,
    repo: SshHostRepository,
    name: String,
    allowedNames: Set<String>?,
    timeoutMs: Int,
    sessionRef: AtomicReference<Session?>,
    block: (Session) -> JsonObject,
): JsonObject {
    if (allowedNames != null && name !in allowedNames) {
        return buildJsonObject { put("error", "host_not_enabled_for_assistant") }
    }
    val h = repo.getByName(name)
        ?: return buildJsonObject { put("error", "no saved host: $name") }
    val auth = SshAuth(password = h.password, privateKey = h.privateKey, passphrase = h.passphrase)
    if (!auth.isUsable()) {
        return buildJsonObject { put("error", "saved host has no usable credentials") }
    }
    val proxyChoice = currentAppSshProxy(h.host, h.port)
    val factory = DefaultRouteSshSocketFactory()
    val startedAt = System.currentTimeMillis()
    return runInterruptible(Dispatchers.IO) {
        val jsch = newJSch(context)
        val session = try {
            openSshSession(jsch, h.host, h.port, h.user, auth, timeoutMs, factory, proxyChoice?.proxy)
        } catch (e: Throwable) {
            Log.w(TAG_SFTP, "ssh handshake failed", e)
            val failure = factory.connectFailure
            if (failure != null) {
                return@runInterruptible unreachableEnvelope(
                    h.host, h.port,
                    if (proxyChoice == null) resolveToIPv4(h.host) ?: h.host else h.host,
                    failure,
                    System.currentTimeMillis() - startedAt,
                    proxyChoice?.dialHost,
                )
            }
            return@runInterruptible wrapConnectError(h.host, e, proxyChoice?.dialHost)
        }
        sessionRef.set(session)
        try {
            block(session)
        } finally {
            sessionRef.set(null)
            try { session.disconnect() } catch (_: Throwable) {}
        }
    }
}

/**
 * Opens the file an upload should read. `source=local` stays on the phone path guard.
 * `source=workspace` resolves an absolute Rootfs path inside the calling assistant's bound
 * workspace and never leaves that root.
 */
private fun workspaceRepositoryOr(injected: WorkspaceRepository?): WorkspaceRepository =
    injected ?: org.koin.java.KoinJavaComponent.getKoin().get()

private suspend fun resolveUploadSource(
    workspaceRepository: WorkspaceRepository?,
    workspaceId: String?,
    localPath: String,
    source: String,
): File {
    if (source == "workspace") {
        val id = workspaceId?.takeIf { it.isNotBlank() }
            ?: error("The calling assistant has no bound Workspace")
        val resolved = workspaceRepositoryOr(workspaceRepository).resolveRootfsFileOrNull(id, localPath)
        return resolved ?: error("workspace file not found: $localPath")
    }
    require(source == "local") { "source must be local or workspace" }
    val expanded = AgentWorkspace.expand(localPath)
    require(File(expanded).isAbsolute) { "Local path must be absolute or start with ~/" }
    PathSafetyGuard.check(expanded)?.let { error(it.detail) }
    allFilesAccessGuard(expanded)?.let { error(it) }
    val file = File(expanded)
    if (!file.exists() || !file.isFile) error("local file not found: $localPath")
    return file
}

/** Upload a local file to a saved-host's remote path via SFTP. */
fun sshUploadTool(
    context: Context,
    repo: SshHostRepository,
    allowedNames: Set<String>? = null,
    workspaceId: String? = null,
    workspaceRepository: WorkspaceRepository? = null,
): Tool = Tool(
    name = "ssh_upload",
    description = """
        Upload a file to a remote path on a saved SSH host using SFTP. local_path is a phone
        path by default (absolute, or ~/ for the agent workspace). Set source=workspace to read
        an absolute path inside the calling assistant's bound Workspace instead, including
        /workspace, bind mounts and the rest of the rootfs. The remote directory must already exist.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("name", buildJsonObject { put("type", "string"); put("description", "Saved host name") })
                put("local_path", buildJsonObject { put("type", "string"); put("description", "Phone path, or a Workspace path when source=workspace") })
                put("source", buildJsonObject { put("type", "string"); put("description", "local (default) or workspace") })
                put("remote_path", buildJsonObject { put("type", "string"); put("description", "Absolute remote path (full filename)") })
                put("timeout_seconds", buildJsonObject { put("type", "integer"); put("description", "Total timeout, default 60, max 600") })
            },
            required = listOf("name", "local_path", "remote_path")
        )
    },
    execute = { input ->
        val p = input.jsonObject
        val name = p["name"]?.jsonPrimitive?.contentOrNull ?: error("name is required")
        val localPath = p["local_path"]?.jsonPrimitive?.contentOrNull ?: error("local_path is required")
        val source = p["source"]?.jsonPrimitive?.contentOrNull ?: "local"
        val remotePath = p["remote_path"]?.jsonPrimitive?.contentOrNull ?: error("remote_path is required")
        val timeoutSec = (p["timeout_seconds"]?.jsonPrimitive?.intOrNull ?: 60).coerceIn(1, 600)
        val localFile = try {
            resolveUploadSource(workspaceRepository, workspaceId, localPath, source)
        } catch (e: IllegalArgumentException) {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject { put("error", e.message ?: "bad path") }.toString()))
        } catch (e: IllegalStateException) {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject { put("error", e.message ?: "bad path") }.toString()))
        }
        val payload = runCancellableSshOp(timeoutSec * 1000L) { sessionRef ->
            withSavedHostSession(context, repo, name, allowedNames, timeoutSec * 1000, sessionRef) { session ->
                val sftp = openSftp(session)
                try {
                    localFile.inputStream().use { input -> sftp.put(input, remotePath) }
                    buildJsonObject {
                        put("success", true)
                        put("remote_path", remotePath)
                        put("bytes", localFile.length())
                    }
                } catch (e: Throwable) {
                    buildJsonObject { put("error", "sftp put failed: ${e.message ?: "unknown"}") }
                } finally {
                    try { sftp.disconnect() } catch (_: Throwable) {}
                }
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

/** Download a remote file from a saved host to a local path via SFTP. */
fun sshDownloadTool(
    context: Context,
    repo: SshHostRepository,
    allowedNames: Set<String>? = null,
    workspaceId: String? = null,
    workspaceRepository: WorkspaceRepository? = null,
): Tool = Tool(
    name = "ssh_download",
    description = """
        Download a remote file from a saved SSH host using SFTP. local_path is a phone path by
        default (absolute, or ~/ for the agent workspace); its parent directory must already exist.
        Set source=workspace to write an absolute path inside the calling assistant's bound
        Workspace instead. Parent directories are created and an existing file is replaced.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("name", buildJsonObject { put("type", "string"); put("description", "Saved host name") })
                put("remote_path", buildJsonObject { put("type", "string"); put("description", "Absolute remote file path") })
                put("local_path", buildJsonObject { put("type", "string"); put("description", "Phone path, or a Workspace path when source=workspace") })
                put("source", buildJsonObject { put("type", "string"); put("description", "local (default) or workspace") })
                put("timeout_seconds", buildJsonObject { put("type", "integer"); put("description", "Total timeout, default 60, max 600") })
            },
            required = listOf("name", "remote_path", "local_path")
        )
    },
    execute = { input ->
        val p = input.jsonObject
        val name = p["name"]?.jsonPrimitive?.contentOrNull ?: error("name is required")
        val remotePath = p["remote_path"]?.jsonPrimitive?.contentOrNull ?: error("remote_path is required")
        val localPath = p["local_path"]?.jsonPrimitive?.contentOrNull ?: error("local_path is required")
        val source = p["source"]?.jsonPrimitive?.contentOrNull ?: "local"
        val timeoutSec = (p["timeout_seconds"]?.jsonPrimitive?.intOrNull ?: 60).coerceIn(1, 600)
        if (source != "local" && source != "workspace") {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject { put("error", "source must be local or workspace") }.toString()
            ))
        }
        if (source == "workspace" && workspaceId.isNullOrBlank()) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject { put("error", "The calling assistant has no bound Workspace") }.toString()
            ))
        }
        val localFile = if (source == "local") {
            val expanded = AgentWorkspace.expand(localPath)
            if (!File(expanded).isAbsolute) {
                return@Tool listOf(UIMessagePart.Text(
                    buildJsonObject { put("error", "Local path must be absolute or start with ~/") }.toString()
                ))
            }
            PathSafetyGuard.check(expanded)?.let { guard ->
                return@Tool listOf(UIMessagePart.Text(buildJsonObject { put("error", guard.detail) }.toString()))
            }
            allFilesAccessGuard(expanded)?.let { detail ->
                return@Tool listOf(UIMessagePart.Text(buildJsonObject { put("error", detail) }.toString()))
            }
            File(expanded).also { file ->
                try { file.parentFile?.mkdirs() } catch (_: Throwable) {}
            }
        } else {
            null
        }
        val stagedRef = java.util.concurrent.atomic.AtomicReference<Pair<File, Long>?>(null)
        val payload = runCancellableSshOp(timeoutSec * 1000L) { sessionRef ->
            withSavedHostSession(context, repo, name, allowedNames, timeoutSec * 1000, sessionRef) { session ->
                val sftp = openSftp(session)
                var ok = false
                var written: File? = localFile
                // The SFTP callback is not a coroutine. Stage the remote file, then import
                // it on the coroutine below — importRootfsBytes is suspending.
                try {
                    if (source == "workspace") {
                        val stage = File.createTempFile("ssh-dl-", ".part", context.cacheDir)
                        written = stage
                        try {
                            stage.outputStream().use { output -> sftp.get(remotePath, output) }
                            stagedRef.set(stage to stage.length())
                            written = null
                        } catch (e: Throwable) {
                            stage.delete()
                            written = null
                            throw e
                        }
                        ok = true
                        buildJsonObject {
                            put("success", true)
                            put("local_path", localPath)
                            put("bytes", stage.length())
                        }
                    } else {
                        val file = localFile!!
                        file.outputStream().use { output -> sftp.get(remotePath, output) }
                        written = file
                        ok = true
                        buildJsonObject {
                            put("success", true)
                            put("local_path", file.absolutePath)
                            put("bytes", file.length())
                        }
                    }
                } catch (e: Throwable) {
                    buildJsonObject { put("error", "sftp get failed: ${e.message ?: "unknown"}") }
                } finally {
                    // Delete partial files left by a failed get. Otherwise the user is left
                    // with a half-written file that LOOKS like a successful download —
                    // misleading and (for binaries) dangerous to execute.
                    if (!ok) try { written?.takeIf { it.exists() }?.delete() } catch (_: Throwable) {}
                    try { sftp.disconnect() } catch (_: Throwable) {}
                }
            }
        }
        val staged = stagedRef.get()
        if (staged != null) {
            val (stage, _) = staged
            try {
                if (payload.get("error") == null) {
                    stage.inputStream().use { input ->
                        workspaceRepositoryOr(workspaceRepository).importRootfsBytes(workspaceId!!, localPath, input)
                    }
                }
            } catch (e: Throwable) {
                return@Tool listOf(UIMessagePart.Text(
                    buildJsonObject { put("error", "workspace import failed: ${e.message ?: "unknown"}") }.toString()
                ))
            } finally {
                stage.delete()
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
