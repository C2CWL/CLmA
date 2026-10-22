package com.clma.app.data.ai.tools.local

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.util.Properties
import java.util.concurrent.atomic.AtomicReference

private const val TAG_SSH = "SshTool"

/**
 * Per-candidate TCP connect timeout for [NetworkRaceSocketFactory]. Candidates are tried in
 * order and the first successful socket is handed straight to JSch, so a device with ~3
 * transports spends at most ~3x this before giving up, on a par with the old design's worst
 * case (a 2.5s parallel probe plus a 10s JSch socket).
 */
private const val CANDIDATE_CONNECT_TIMEOUT_MS = 4_000
/** Connect timeout for the single-transport (default-route) factory. */
private const val SOCKET_CONNECT_TIMEOUT_MS = 10_000

/**
 * Keep-alive parameters for every JSch session. Without these, JSch sends nothing on an idle
 * channel, so a long-running command (e.g. a build, a `tail -f`) sits silent long enough for
 * an intermediate NAT/firewall to drop the connection's state table entry — the next byte
 * then hits a black hole and the command "dies" with no error. 30s × 3 missed = ~90s before
 * JSch itself declares the session dead, which keeps NATs warm while still failing fast on a
 * genuinely-gone server.
 */
private const val SERVER_ALIVE_INTERVAL_MS = 30_000
private const val SERVER_ALIVE_COUNT_MAX = 3

/**
 * Process-lifetime DNS cache shared by every SSH session-build path. 60s TTL — long enough
 * to spare a 15-minute-interval workflow a fresh lookup on every run, short enough that a
 * DHCP/DNS change is picked up quickly. Self-registers with NetworkChangeMonitor so a
 * default-network handoff drops the cache (a cached IP from the old network's DNS could be
 * wrong on the new one).
 */
internal val sshDnsCache: DnsCache = DnsCache().also { cache ->
    runCatching {
        com.clma.app.utils.NetworkChangeMonitor.addNetworkChangeListener {
            cache.invalidateAll()
        }
    }
}

/**
 * Caps on stdout / stderr returned to the LLM. Without these, `cat /var/log/syslog` /
 * `journalctl` / `ls -R /` push megabytes into the next prompt, blowing the context
 * budget on a single tool call. Mirrors the Termux tool's caps so behaviour is
 * consistent across local and remote shell.
 */
private const val MAX_RETURNED_STDOUT = 8_000
private const val MAX_RETURNED_STDERR = 2_000

/**
 * Bounded stdout/stderr sink for a remote command. JSch streams the remote process's output
 * into the OutputStream we hand the channel; a previous version pointed that at an unbounded
 * ByteArrayOutputStream and only truncated AFTER, so `cat /dev/zero | base64` (or any chatty
 * remote command) could pull megabytes into the heap and OOM the app before we ever truncated.
 * This keeps at most [cap] + [SLACK] bytes in memory and discards the rest, counting what it
 * threw away so the user still sees a faithful "[truncated; N bytes more]" suffix.
 *
 * The slack lets us hold a few bytes past [cap] so [snapshot] can render the truncation marker
 * without splitting a trailing multi-byte UTF-8 sequence. [cap] and the discarded-byte count
 * are both measured in BYTES so multibyte (CJK / emoji) output is sized honestly: a char-based
 * kept slice would over- or under-shoot the byte budget and, worse, falsely report truncation
 * when the byte count (total) exceeds the char-count cap even though nothing was discarded.
 */
internal class BoundedOutputStream(private val cap: Int) : java.io.OutputStream() {
    private val buf = ByteArrayOutputStream(minOf(cap + SLACK, 16_384))
    /** Total bytes the remote process wrote, including the ones we discarded. */
    private var total: Long = 0L

    override fun write(b: Int) {
        total += 1
        if (buf.size() < cap + SLACK) buf.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        total += len
        val remaining = (cap + SLACK) - buf.size()
        if (remaining > 0) buf.write(b, off, minOf(len, remaining))
    }

    /**
     * Render the captured bytes with a "[truncated; N bytes more]" tail derived from the true
     * byte count we observed — the discarded bytes never hit the heap, so a post-hoc length
     * check on the buffer alone couldn't know about them.
     *
     * Everything here is byte-accurate: we keep at most [cap] bytes (snapped down to a UTF-8
     * code-point boundary so a trailing multi-byte sequence is never split) and report the
     * remainder as (total bytes written minus bytes kept). When nothing exceeded [cap] there is no
     * marker, even for multibyte text whose byte count outruns its char count.
     */
    fun snapshot(): String {
        val bytes = buf.toByteArray()
        if (total <= cap.toLong()) return String(bytes, Charsets.UTF_8)
        // Keep up to [cap] bytes, backing off to a code-point boundary so we don't slice a
        // multi-byte UTF-8 sequence in half. The buffer holds cap + SLACK bytes, so there is
        // always at least one whole code point's worth of headroom past the cut.
        var keep = minOf(cap, bytes.size)
        while (keep in 1 until bytes.size && (bytes[keep].toInt() and 0xC0) == 0x80) keep--
        val kept = String(bytes, 0, keep, Charsets.UTF_8)
        return kept + "\n…[truncated; ${total - keep.toLong()} bytes more]"
    }

    private companion object {
        /** Headroom past [cap] so a final multi-byte UTF-8 sequence isn't split. */
        const val SLACK = 4_096
    }
}

/**
 * Single-quote [s] for safe embedding in a POSIX shell command. Wraps the whole string in
 * single quotes and rewrites each embedded single quote as the standard `'\''` sequence
 * (close-quote, escaped literal quote, reopen-quote). Lets us splice an arbitrary user command
 * into a wrapper without a quoting injection.
 */
internal fun shellSingleQuote(s: String): String =
    "'" + s.replace("'", "'\\''") + "'"

/**
 * Wrap [command] so it runs fully detached from the SSH exec channel and returns immediately.
 *
 * A bare `cmd &` does NOT free the exec channel: the backgrounded child inherits the channel's
 * stdout/stderr pipes, so JSch never sees channel EOF and ssh_exec blocks until its timeout even
 * though the foreground shell already exited. We instead launch under `nohup` (immune to the
 * SIGHUP fired when the session closes), redirect all three std streams away from the channel
 * pipes, background it, and echo the launched PID. The channel then reaches EOF at once and the
 * call returns the PID instead of hanging.
 */
internal fun wrapDetachedCommand(command: String): String =
    "nohup sh -c ${shellSingleQuote(command)} >/dev/null 2>&1 </dev/null & echo \"rikkahub_bg_pid=\$!\""

/**
 * Resolves [host] to an IPv4 address string. JSch's `Socket(addr, port)` does NOT implement
 * Happy Eyeballs — it sits on the IPv6 SYN until the connect timeout fires, even when the
 * server only listens on IPv4. Termux's OpenSSH races both stacks in parallel and never
 * sees this failure mode, which is why `ssh user@host` from Termux works while our direct
 * JSch path on the same Pixel times out.
 *
 * Returns null if [host] is already a literal IP, if no IPv4 record exists, or if DNS fails.
 * Caller falls back to the original [host] string in that case.
 *
 * Successful resolutions are memoised in [sshDnsCache] (60s TTL) so a workflow that SSHes to
 * the same host on a tight interval doesn't pay a DNS round-trip every connect. A literal IP
 * input bypasses the cache entirely — it's not a name, there's nothing to resolve or stale.
 * Failures are never cached, so a transient DNS hiccup can't poison the next 60s of connects.
 */
internal fun resolveToIPv4(host: String): String? {
    sshDnsCache.get(host)?.let { cached ->
        Log.i(TAG_SSH, "resolveToIPv4: $host -> $cached (dns cache hit)")
        return cached
    }
    return try {
        val addrs = InetAddress.getAllByName(host)
        // Already a literal IP (v4 or v6)? getAllByName returns it as-is and the printed
        // hostAddress equals the input (modulo case). Don't try to "resolve" it further.
        if (addrs.isNotEmpty() && addrs[0].hostAddress.equals(host, ignoreCase = true)) return null
        val v4 = addrs.firstOrNull { it is Inet4Address } ?: return null
        v4.hostAddress?.also {
            sshDnsCache.put(host, it)
            Log.i(TAG_SSH, "resolveToIPv4: $host -> $it (skipping ${addrs.size - 1} other records)")
        }
    } catch (t: Throwable) {
        Log.w(TAG_SSH, "resolveToIPv4: $host failed", t)
        null
    }
}

/**
 * Returns every Network the device has, ordered for [NetworkRaceSocketFactory]. Each entry is
 * a (label, network-or-null) pair; the null entry means "do not bind, let Android pick the
 * default route" and is placed first.
 *
 * All WiFi networks the device exposes are included individually (a phone may have a
 * saved-but-inactive hotspot AND the active WiFi visible at the same time; filtering down to
 * one would skip the working network on adaptive-routing devices), VALIDATED-INTERNET first.
 *
 * The order matters now: the factory walks this list instead of racing it in parallel, so
 * keeping the default route first keeps the common case down to a single socket.
 */
private fun enumerateCandidateNetworks(ctx: Context): List<Pair<String, Network?>> {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return listOf("default" to null)
    val out = mutableListOf<Pair<String, Network?>>()
    // The default route goes first: it is the one Android itself would pick (so it honours an
    // active VPN) and it wins in the common case.
    out += "default" to null
    // allNetworks deprecated at API 31; the recommended NetworkCallback path is async and
    // doesn't fit our synchronous enumeration here. Behaviour-equivalent and still functional.
    @Suppress("DEPRECATION")
    val all = try { cm.allNetworks.toList() } catch (_: Throwable) { emptyList() }
    fun caps(n: Network) = try { cm.getNetworkCapabilities(n) } catch (_: Throwable) { null }

    val wifis = all.filter { caps(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        .sortedByDescending {
            val c = caps(it)
            val validated = c?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            val internet = c?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            (if (validated) 2 else 0) + (if (internet) 1 else 0)
        }
    wifis.forEachIndexed { i, n ->
        out += (if (wifis.size == 1) "wifi" else "wifi$i") to n
    }
    all.firstOrNull { caps(it)?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true }
        ?.let { out += "ethernet" to it }
    all.firstOrNull { caps(it)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true }
        ?.let { out += "cellular" to it }
    return out
}

/**
 * Auth bundle. Either [password] or [privateKey] must be non-blank for connect to succeed.
 */
internal data class SshAuth(
    val password: String? = null,
    val privateKey: String? = null,
    val passphrase: String? = null,
)

internal fun SshAuth.isUsable() = !password.isNullOrBlank() || !privateKey.isNullOrBlank()

/**
 * Construct a JSch instance pre-loaded with the app's persistent known_hosts file.
 *
 * Host-key policy is trust-on-first-use, implemented explicitly (see [TofuUserInfo]): a host we
 * have never seen is accepted and its key recorded, a host whose key changed fails the connect
 * so the user can decide whether to call `ssh_forget_host_key`.
 */
internal fun newJSch(context: Context): JSch {
    val jsch = JSch()
    val knownHosts = knownHostsFile(context)
    if (!knownHosts.exists()) {
        try { knownHosts.createNewFile() } catch (_: Throwable) {}
    }
    try { jsch.setKnownHosts(knownHosts.absolutePath) } catch (_: Throwable) {}
    return jsch
}

internal fun knownHostsFile(context: Context): File = File(context.filesDir, "known_hosts")

/**
 * Answers JSch's host-key and credential prompts without a UI.
 *
 * JSch 0.2.21 does NOT implement OpenSSH's `accept-new`. `Session.checkHost` only branches on
 * the literal values `ask` / `yes` / `no`, so `accept-new` fell through *every* branch: an
 * unknown host key was neither verified nor stored (which is why the settings page's "known
 * host keys" list stayed empty no matter how many times a host connected, despite its "a key is
 * recorded the first time a host is reached" promise) and a *changed* key was accepted in
 * silence — i.e. there was no trust-on-first-use and no man-in-the-middle protection at all.
 *
 * We therefore keep JSch's own `ask` mode and answer the prompts programmatically: "yes" for a
 * host we have never seen (JSch then persists that key into known_hosts itself) and "no" for a
 * host whose key changed (JSch then throws "HostKey has been changed", which
 * [wrapConnectError] surfaces as `host_key_changed` with the `ssh_forget_host_key` hint).
 */
private class TofuUserInfo(
    private val password: String?,
    private val passphrase: String?,
) : com.jcraft.jsch.UserInfo {
    override fun getPassphrase(): String? = passphrase

    override fun getPassword(): String? = password

    override fun promptPassword(message: String?): Boolean = !password.isNullOrBlank()

    override fun promptPassphrase(message: String?): Boolean = !passphrase.isNullOrBlank()

    /**
     * JSch asks this for an unseen host and for a changed one; only the wording tells them
     * apart. Accept the former (so the key is recorded — the TOFU half), refuse the latter so a
     * changed key fails loudly instead of being trusted.
     */
    override fun promptYesNo(message: String?): Boolean =
        !message.orEmpty().contains("IDENTIFICATION HAS CHANGED", ignoreCase = true)

    override fun showMessage(message: String?) {
        Log.i(TAG_SSH, "jsch: ${message.orEmpty().lineSequence().firstOrNull().orEmpty()}")
    }
}

/**
 * Connect [session] unless it is already up.
 *
 * JSch's [Session.connect] throws `JSchException("session is already connected")` when it is
 * called a second time. That reads like an SSH failure but is a caller mistake, and it has
 * bitten us: the settings page's connection test called `openSshSession` (which connects) and
 * then `Session.connect` again, so a perfectly healthy handshake was reported as
 * `connect_failed`. Every caller goes through here instead, which makes a redundant call
 * harmless rather than fatal.
 */
internal fun connectIfNeeded(session: Session, timeoutMs: Int) {
    if (!session.isConnected) session.connect(timeoutMs)
}

/**
 * Open a connected SSH session. Caller MUST call [Session.disconnect] in a finally block.
 * Throws on auth/connect failure (caller wraps in try/catch and surfaces a JSON error).
 */
internal fun openSshSession(
    jsch: JSch,
    host: String,
    port: Int,
    user: String,
    auth: SshAuth,
    timeoutMs: Int,
    socketFactory: com.jcraft.jsch.SocketFactory? = null,
): Session {
    if (!auth.privateKey.isNullOrBlank()) {
        val keyBytes = auth.privateKey.toByteArray(Charsets.UTF_8)
        val passBytes = auth.passphrase?.toByteArray(Charsets.UTF_8)
        jsch.addIdentity("rikkahub-ssh-key-${System.nanoTime()}", keyBytes, null, passBytes)
    }
    // Force IPv4 resolution before handing the address to JSch. Solves the "ssh works in
    // Termux but times out in our app" Pixel-only failure: Android's DnsResolver returns
    // AAAA first and JSch sits on the IPv6 SYN for the full timeout instead of falling
    // back to v4. setHostKeyAlias keeps known_hosts comparisons keyed by the human-readable
    // hostname so trust-on-first-use still works the same.
    val ipv4 = resolveToIPv4(host)
    val effectiveHost = ipv4 ?: host
    val session = jsch.getSession(user, effectiveHost, port)
    if (ipv4 != null && ipv4 != host) {
        session.setHostKeyAlias(host)
    }
    if (!auth.password.isNullOrBlank()) session.setPassword(auth.password)
    // Trust on first use, without a UI prompt: see [TofuUserInfo]. (Not `accept-new` — JSch
    // 0.2.21 ignores that value entirely, storing nothing and verifying nothing.)
    session.setUserInfo(TofuUserInfo(auth.password, auth.passphrase))
    session.setConfig(Properties().apply {
        // "ask" + [TofuUserInfo] = trust on first use, fail if a known host's key changes.
        setProperty("StrictHostKeyChecking", "ask")
        setProperty("PreferredAuthentications", "publickey,keyboard-interactive,password")
    })
    // Install a socket factory so JSch's internal sockets get a bounded connect timeout and,
    // when the caller passes a [NetworkRaceSocketFactory], so the SSH connection is placed on
    // the first transport that can actually reach the host (bypassing Android's adaptive
    // routing, which would otherwise re-route app traffic away from the chosen WiFi LAN). The
    // default is a single-candidate factory that leaves routing to Android.
    session.setSocketFactory(
        socketFactory ?: NetworkRaceSocketFactory(
            listOf("default" to null),
            SOCKET_CONNECT_TIMEOUT_MS,
        )
    )
    // Keep-alive: send a server-alive probe every 30s of channel idle, give up after 3
    // unanswered. Without this an intermediate NAT/firewall silently drops the connection's
    // state entry during a long-running command and the session black-holes. This is the
    // single shared session-build path for ssh_exec, ssh_exec_saved, and SFTP
    // upload/download, so setting it here covers every JSch session in the app.
    session.serverAliveInterval = SERVER_ALIVE_INTERVAL_MS
    session.serverAliveCountMax = SERVER_ALIVE_COUNT_MAX
    connectIfNeeded(session, timeoutMs)
    return session
}

/**
 * JSch [SocketFactory] that picks the transport for the SSH connection itself.
 *
 * The previous design raced a *bare TCP* probe across every candidate transport before JSch
 * opened its own socket. Each probe was a connect-then-immediately-close, which sshd logs as
 * `Connection closed by ... [preauth]`. Those log lines are the structural source of the
 * fail2ban bans that took the VPS ssh channel down (see M05 T-01), and the winning probe
 * socket was thrown away regardless so JSch could open a *second*, real socket on that same
 * network.
 *
 * Here the search and the connection are the same act: [createSocket] walks [candidates] in
 * order and returns the first socket whose TCP connect succeeds, and JSch performs the SSH
 * handshake on *that* socket. A candidate whose connect fails never completes a TCP handshake,
 * so the far end never logs anything for it. Zero throwaway connections, no probe phase.
 *
 * Failure details for every candidate are retained in [failures] so the caller can still emit
 * the per-transport `tcp_unreachable` envelope.
 */
internal class NetworkRaceSocketFactory(
    private val candidates: List<Pair<String, Network?>>,
    private val connectTimeoutMs: Int,
) : com.jcraft.jsch.SocketFactory {
    private val _failures = mutableListOf<Pair<String, String>>()

    /** Label of the transport whose socket was handed to JSch, or null if none succeeded. */
    @Volatile
    var winningLabel: String? = null
        private set

    /** (label, reason) for every candidate whose TCP connect did not succeed. */
    val failures: List<Pair<String, String>> get() = _failures.toList()

    override fun createSocket(host: String, port: Int): java.net.Socket {
        for ((label, network) in candidates) {
            val s = java.net.Socket()
            try {
                if (network != null) {
                    try { network.bindSocket(s) } catch (t: Throwable) {
                        Log.w(TAG_SSH, "bindSocket to $label failed", t)
                    }
                }
                s.connect(java.net.InetSocketAddress(host, port), connectTimeoutMs)
                winningLabel = label
                Log.i(TAG_SSH, "ssh socket up via $label")
                return s
            } catch (e: Throwable) {
                _failures += label to "${e::class.java.simpleName}: ${e.message ?: "unknown"}"
                try { s.close() } catch (_: Throwable) {}
            }
        }
        throw java.net.SocketException("no transport could reach $host:$port")
    }

    override fun getInputStream(socket: java.net.Socket): java.io.InputStream =
        socket.getInputStream()

    override fun getOutputStream(socket: java.net.Socket): java.io.OutputStream =
        socket.getOutputStream()
}

/**
 * Build the transport-racing socket factory for [context]'s current networks. Shared by the
 * exec and SFTP paths so both pick a transport the same way.
 */
internal fun networkRaceSocketFactory(context: Context): NetworkRaceSocketFactory =
    NetworkRaceSocketFactory(enumerateCandidateNetworks(context), CANDIDATE_CONNECT_TIMEOUT_MS)

/**
 * Standard envelope for "we couldn't reach the host on any transport". Surfaces the per-
 * transport failure reasons so the LLM can quote them back to the user when explaining what
 * went wrong, plus the recovery hint for the most common Android routing pitfalls.
 */
internal fun unreachableEnvelope(
    host: String,
    port: Int,
    resolvedIp: String,
    failures: List<Pair<String, String>>,
    totalMs: Long,
): JsonObject =
    buildJsonObject {
        put("error", "tcp_unreachable")
        put("host", host)
        put("ip", resolvedIp)
        put("port", port)
        put("attempts", buildJsonObject {
            failures.forEach { (label, reason) -> put(label, reason) }
        })
        put("recovery", "Direct TCP to $resolvedIp:$port failed across every available " +
            "network (${totalMs}ms total). If Termux ssh from the same device reaches " +
            "this host, RikkaHub's process is being filtered. Check Settings → Network → " +
            "Private DNS (try Off), any active VPN's per-app routing, and Settings → Apps → " +
            "RikkaHub → Mobile data & Wi-Fi (enable Background data and Unrestricted data usage).")
    }

/** Run a single command on an open session. Returns a JSON object with exit_code/stdout/stderr. */
internal fun runOnSession(session: Session, command: String, timeoutMs: Int, stdin: String? = null): JsonObject {
    // Bounded sinks: cap peak memory at a few KB regardless of how much the remote command
    // emits, so a high-throughput command can't OOM the app before we truncate. See
    // [BoundedOutputStream].
    val stdout = BoundedOutputStream(MAX_RETURNED_STDOUT)
    val stderr = BoundedOutputStream(MAX_RETURNED_STDERR)
    val channel = session.openChannel("exec") as ChannelExec
    var hitDeadline = false
    try {
        channel.setCommand(command)
        channel.outputStream = stdout
        channel.setErrStream(stderr)
        // Feed stdin then EOF. Real `ssh host 'cmd'` closes stdin when there's no piped input,
        // but JSch leaves the channel's stdin open with neither data nor EOF unless we set an
        // input stream — so a command that reads stdin (`cat > file`, `read`, `base64 -d`)
        // blocks until the deadline. An empty stream yields an immediate EOF; a non-null [stdin]
        // is delivered first and then EOF, which is the quote-free way to pipe data or write a
        // file without a heredoc.
        channel.setInputStream(ByteArrayInputStream((stdin ?: "").toByteArray(Charsets.UTF_8)))
        channel.connect(timeoutMs)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!channel.isClosed) {
            if (System.currentTimeMillis() >= deadline) { hitDeadline = true; break }
            try { Thread.sleep(50) } catch (_: InterruptedException) {
                // Coroutine cancellation propagated through runInterruptible. Bail out.
                Thread.currentThread().interrupt()
                break
            }
        }
        if (hitDeadline) {
            // Command outlasted its timeout. Channel.exitStatus would return -1 here, which
            // earlier versions reported as exit_code=-1 + success=false — indistinguishable
            // from a real exit code -1. Make the timeout explicit so the model can choose
            // to bump timeout_seconds rather than retrying with a different command.
            return buildJsonObject {
                put("error", "command_timeout")
                put("recovery", "Command did not complete within ${timeoutMs / 1000}s. Bump " +
                    "timeout_seconds, or pass background=true to launch it detached so the call " +
                    "returns immediately with the launched PID. Partial stdout/stderr captured " +
                    "before the timeout is included.")
                put("partial_stdout", stdout.snapshot())
                put("partial_stderr", stderr.snapshot())
            }
        }
        val exitCode = channel.exitStatus
        return buildJsonObject {
            put("success", exitCode == 0)
            put("exit_code", exitCode)
            put("stdout", stdout.snapshot())
            put("stderr", stderr.snapshot())
        }
    } finally {
        try { channel.disconnect() } catch (_: Throwable) {}
    }
}

/**
 * One-shot SSH exec — for hosts the user doesn't want to save (anonymous / ad-hoc / one-time).
 * Prefer vault refs (`password_secret` / `private_key_secret` / `stdin_secret` from
 * `request_secret`) so credentials never enter chat history. For frequent hosts use
 * save_ssh_host + ssh_exec_saved.
 */
fun sshExecTool(
    context: Context,
    secrets: com.clma.app.data.secrets.AgentSecretsStore,
): Tool = Tool(
    name = "ssh_exec",
    description = """
        Connect to a remote host via SSH and run a single shell command. Returns stdout, stderr,
        and exit code. Prefer password_secret / private_key_secret / passphrase_secret /
        stdin_secret (vault names from request_secret) over plaintext so secrets never enter
        chat history. For hosts you'll use repeatedly, prefer save_ssh_host + ssh_exec_saved.
        For destructive or system-level commands you should explicitly confirm with the user
        before invoking.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("host", buildJsonObject { put("type", "string"); put("description", "Hostname or IP address") })
                put("port", buildJsonObject { put("type", "integer"); put("description", "SSH port, default 22") })
                put("user", buildJsonObject { put("type", "string"); put("description", "SSH username") })
                put("password_secret", buildJsonObject {
                    put("type", "string")
                    put("description", "Vault name for the password (from request_secret). Preferred over password.")
                })
                put("private_key_secret", buildJsonObject {
                    put("type", "string")
                    put("description", "Vault name for the private key PEM. Preferred over private_key.")
                })
                put("passphrase_secret", buildJsonObject {
                    put("type", "string")
                    put("description", "Vault name for the key passphrase. Preferred over passphrase.")
                })
                put("password", buildJsonObject {
                    put("type", "string")
                    put("description", "DEPRECATED plaintext password — use password_secret + request_secret")
                })
                put("private_key", buildJsonObject {
                    put("type", "string")
                    put("description", "DEPRECATED plaintext PEM — use private_key_secret")
                })
                put("passphrase", buildJsonObject {
                    put("type", "string")
                    put("description", "DEPRECATED plaintext passphrase — use passphrase_secret")
                })
                put("command", buildJsonObject { put("type", "string"); put("description", "Shell command to run on the remote host") })
                put("stdin_secret", buildJsonObject {
                    put("type", "string")
                    put("description", "Vault name whose value is piped to stdin (preferred for passwords)")
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
                    put("description", "Total timeout including connect+exec, default 30, max 300")
                })
            },
            required = listOf("host", "user", "command"),
        )
    },
    execute = {
        val p = it.jsonObject
        val host = p["host"]?.jsonPrimitive?.contentOrNull ?: error("host is required")
        val user = p["user"]?.jsonPrimitive?.contentOrNull ?: error("user is required")
        val command = p["command"]?.jsonPrimitive?.contentOrNull ?: error("command is required")
        val port = p["port"]?.jsonPrimitive?.intOrNull ?: 22
        val stdin = resolveSecretOrPlain(
            secrets,
            refName = p["stdin_secret"]?.jsonPrimitive?.contentOrNull,
            plain = p["stdin"]?.jsonPrimitive?.contentOrNull,
            field = "stdin",
        ).getOrElse { return@Tool secretError(it) }
        val background = p["background"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
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
        val auth = SshAuth(
            password = password,
            privateKey = privateKey,
            passphrase = passphrase,
        )
        val timeoutSec = (p["timeout_seconds"]?.jsonPrimitive?.intOrNull ?: 30).coerceIn(1, 300)
        if (!auth.isUsable()) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "missing_credentials")
                        put(
                            "detail",
                            "Provide password_secret or private_key_secret (call request_secret first), " +
                                "or legacy password/private_key.",
                        )
                    }.toString(),
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
            execOneShot(context, host, port, user, auth, effectiveCommand, timeoutSec * 1000, sessionRef, stdin)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    },
)

/**
 * Wrap a suspend SSH operation in coroutine-friendly cancellation. JSch's blocking JNI
 * socket reads do NOT honour Thread.interrupt() reliably, so withTimeoutOrNull alone
 * leaves the operation running on the IO thread until the OS TCP timeout (~75s on most
 * kernels) even though we've already returned "timeout" to the model.
 *
 * Strategy: the inner [block] stashes its live Session in [sessionRef] as soon as it's
 * open. If the outer withTimeoutOrNull cancels (or anything else throws), we forcibly
 * disconnect that Session in finally — closing the underlying socket unblocks JSch's JNI
 * read on the IO thread.
 */
internal suspend fun runCancellableSshOp(
    timeoutMs: Long,
    block: suspend (sessionRef: AtomicReference<Session?>) -> JsonObject,
): JsonObject {
    val sessionRef = AtomicReference<Session?>(null)
    return try {
        withTimeoutOrNull(timeoutMs) { block(sessionRef) }
            ?: buildJsonObject { put("error", "timeout") }
    } finally {
        // Forcibly disconnect the session if it's still open. Safe to call on an already-
        // disconnected session — JSch's Session.disconnect is idempotent.
        sessionRef.getAndSet(null)?.let { s ->
            try { s.disconnect() } catch (_: Throwable) {}
        }
    }
}

/**
 * Connect → run → disconnect. The transport search is fused into the JSch socket factory
 * ([NetworkRaceSocketFactory]), so there is no separate probe phase: JSch performs the SSH
 * handshake on the first socket whose TCP connect succeeds. The handshake + exec are blocking,
 * so we hand them off to runInterruptible(IO) which gives us best-effort thread interrupt on
 * coroutine cancellation. The Session is stashed in [sessionRef] so the outer
 * [runCancellableSshOp] can also forcibly disconnect from outside if interrupt isn't honoured
 * by JNI.
 */
internal suspend fun execOneShot(
    context: Context,
    host: String,
    port: Int,
    user: String,
    auth: SshAuth,
    command: String,
    timeoutMs: Int,
    sessionRef: AtomicReference<Session?>,
    stdin: String? = null,
): JsonObject {
    val factory = networkRaceSocketFactory(context)
    return runInterruptible(Dispatchers.IO) {
        val jsch = newJSch(context)
        val handshakeStart = System.currentTimeMillis()
        val session = try {
            openSshSession(jsch, host, port, user, auth, timeoutMs, factory)
        } catch (e: Throwable) {
            Log.w(TAG_SSH, "ssh handshake failed in ${System.currentTimeMillis() - handshakeStart}ms", e)
            if (factory.winningLabel == null && factory.failures.isNotEmpty()) {
                return@runInterruptible unreachableEnvelope(
                    host, port, resolveToIPv4(host) ?: host, factory.failures,
                    System.currentTimeMillis() - handshakeStart,
                )
            }
            return@runInterruptible wrapConnectError(host, e)
        }
        sessionRef.set(session)
        Log.i(TAG_SSH, "ssh session up via ${factory.winningLabel ?: "default"} in ${System.currentTimeMillis() - handshakeStart}ms")
        try {
            runOnSession(session, command, timeoutMs, stdin)
        } catch (e: Throwable) {
            buildJsonObject { put("error", "exec failed: ${e.message ?: "unknown"}") }
        } finally {
            sessionRef.set(null)
            try { session.disconnect() } catch (_: Throwable) {}
        }
    }
}

/**
 * Translate JSch connection failures into structured envelopes the LLM can act on. Three
 * distinct branches:
 *   - host_key_changed: server identity differs from known_hosts. Hint to call
 *     ssh_forget_host_key only after explicit user confirmation.
 *   - auth_failed: credentials rejected. Hint to verify with user before retrying so the
 *     model doesn't burn round-trips brute-forcing the same wrong password.
 *   - generic connect_failed: anything else.
 */
internal fun wrapConnectError(host: String, e: Throwable): JsonObject {
    val msg = e.message.orEmpty()
    val isHostKeyChange = msg.contains("HostKey", ignoreCase = true) ||
        msg.contains("host key", ignoreCase = true) ||
        msg.contains("identification has changed", ignoreCase = true) ||
        msg.contains("REMOTE HOST IDENTIFICATION", ignoreCase = true)
    if (isHostKeyChange) {
        return buildJsonObject {
            put("error", "host_key_changed")
            put("host", host)
            put("recovery", "Stored key for $host doesn't match what the server presented. " +
                "If the user trusts this host (e.g. they just reinstalled it), call " +
                "ssh_forget_host_key with host=\"$host\" then retry. Do NOT forget the key " +
                "without explicit user confirmation — a changed key can also indicate an attacker.")
            put("raw", msg)
        }
    }
    val isAuthFailure = msg.contains("Auth fail", ignoreCase = true) ||
        msg.contains("auth cancel", ignoreCase = true) ||
        msg.contains("USERAUTH fail", ignoreCase = true) ||
        msg.contains("Authentication failed", ignoreCase = true) ||
        msg.contains("Permission denied (publickey", ignoreCase = true) ||
        msg.contains("Permission denied (password", ignoreCase = true)
    if (isAuthFailure) {
        return buildJsonObject {
            put("error", "auth_failed")
            put("host", host)
            put("recovery", "Credentials rejected by $host. Verify the password / private_key / " +
                "username with the user before retrying — DO NOT keep guessing or you'll lock " +
                "the account out. If using a private_key, double-check the user passed the " +
                "FULL PEM contents (including the BEGIN/END markers).")
            put("raw", msg)
        }
    }
    return buildJsonObject {
        put("error", "connect_failed")
        put("host", host)
        put("reason", msg.ifBlank { e::class.simpleName ?: "unknown" })
    }
}

/**
 * Remove all stored host keys for [host] from the persistent known_hosts file. Use after the
 * user confirms they reinstalled the remote — the next connect will trust the new key per
 * the accept-new policy.
 *
 * IMPORTANT: JSch's [com.jcraft.jsch.HostKeyRepository.remove] only updates the in-memory
 * KnownHosts; it does not write back to the file. JSch's KnownHosts.dump(OutputStream)
 * exists but is package-private, so we manually re-serialise the in-memory list to the
 * known_hosts(5) format. Without this, the next newJSch() reloads from the unchanged file
 * and the "host key changed" error returns immediately — i.e. ssh_forget_host_key silently
 * no-ops.
 */
internal fun forgetHostKey(context: Context, host: String): Int {
    val jsch = newJSch(context)
    val repo = jsch.hostKeyRepository
    val before = repo.hostKey?.count { it.host == host } ?: 0
    if (before == 0) return 0
    repo.remove(host, null)
    try {
        knownHostsFile(context).bufferedWriter().use { w ->
            repo.hostKey?.forEach { hk ->
                val marker = hk.marker?.takeIf { it.isNotEmpty() }
                val line = buildString {
                    if (marker != null) append('@').append(marker).append(' ')
                    append(hk.host).append(' ')
                    append(hk.type).append(' ')
                    append(hk.key)
                }
                w.write(line)
                w.newLine()
            }
        }
    } catch (e: Throwable) {
        Log.w(TAG_SSH, "forgetHostKey: failed to persist known_hosts after remove", e)
    }
    return before
}
