package me.rerere.rikkahub.ui.pages.setting

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.ai.tools.local.SshAuth
import me.rerere.rikkahub.data.ai.tools.local.forgetHostKey
import me.rerere.rikkahub.data.ai.tools.local.isUsable
import me.rerere.rikkahub.data.ai.tools.local.knownHostsFile
import me.rerere.rikkahub.data.ai.tools.local.networkRaceSocketFactory
import me.rerere.rikkahub.data.ai.tools.local.newJSch
import me.rerere.rikkahub.data.ai.tools.local.openSshSession
import me.rerere.rikkahub.data.ai.tools.local.wrapConnectError
import me.rerere.rikkahub.data.db.entity.SshHostEntity
import me.rerere.rikkahub.data.repository.SshHostRepository
import me.rerere.rikkahub.data.ssh.KnownHostEntry
import me.rerere.rikkahub.data.ssh.SshHostDraft
import me.rerere.rikkahub.data.ssh.parseKnownHosts

/** Outcome of the "test connection" button: a real JSch handshake, never a bare TCP probe. */
data class SshTestResult(
    val ok: Boolean,
    val errorCode: String? = null,
    val detail: String? = null,
)

/**
 * P2-32: backs the SSH host settings page.
 *
 * Hosts live in the `ssh_hosts` Room table and were previously only reachable through the LLM
 * tools (`save_ssh_host` / `delete_ssh_host` / `list_ssh_hosts`). This is the first
 * human-facing surface for that data.
 *
 * The connection test uses [openSshSession] rather than
 * [me.rerere.rikkahub.data.ai.tools.local.execOneShot] — a test opens no channel and runs no
 * command — but it deliberately walks the same candidate transports, because otherwise the
 * page can report a failure for a host the tools reach without trouble (the tools spend their
 * first candidate on the default route too, then fall back to a physical network; a test
 * pinned to the default route alone cannot).
 */
class SettingSshViewModel(
    private val context: Context,
    private val repository: SshHostRepository,
) : ViewModel() {

    private val _hosts = MutableStateFlow<List<SshHostEntity>>(emptyList())
    val hosts: StateFlow<List<SshHostEntity>> = _hosts.asStateFlow()

    private val _knownHosts = MutableStateFlow<List<KnownHostEntry>>(emptyList())
    val knownHosts: StateFlow<List<KnownHostEntry>> = _knownHosts.asStateFlow()

    private val _testingName = MutableStateFlow<String?>(null)
    val testingName: StateFlow<String?> = _testingName.asStateFlow()

    private val _testResult = MutableStateFlow<Pair<String, SshTestResult>?>(null)
    val testResult: StateFlow<Pair<String, SshTestResult>?> = _testResult.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _hosts.value = withContext(Dispatchers.IO) { repository.getAll() }
            reloadKnownHosts()
        }
    }

    private suspend fun reloadKnownHosts() {
        _knownHosts.value = withContext(Dispatchers.IO) {
            val file = knownHostsFile(context)
            if (file.exists()) parseKnownHosts(file.readText()) else emptyList()
        }
    }

    fun save(draft: SshHostDraft) {
        viewModelScope.launch {
            val entity = SshHostEntity(
                name = draft.name.trim(),
                host = draft.host.trim(),
                port = draft.portOrNull() ?: 22,
                user = draft.user.trim(),
                password = draft.password.takeIf { it.isNotBlank() },
                privateKey = draft.privateKey.takeIf { it.isNotBlank() },
                passphrase = draft.passphrase.takeIf { it.isNotBlank() },
                createdAtMs = System.currentTimeMillis(),
            )
            withContext(Dispatchers.IO) {
                val original = draft.originalName?.trim()
                // `name` is the primary key, so a rename is a delete + insert.
                if (original != null && original != entity.name) repository.deleteByName(original)
                repository.upsert(entity)
            }
            refresh()
        }
    }

    fun delete(host: SshHostEntity) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.deleteByName(host.name) }
            refresh()
        }
    }

    /** Forget every stored host key for [entry]'s pattern. Hashed entries cannot be mapped. */
    fun forgetKey(entry: KnownHostEntry) {
        if (entry.isHashed) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { forgetHostKey(context, entry.pattern) }
            reloadKnownHosts()
        }
    }

    fun test(host: SshHostEntity) {
        if (_testingName.value != null) return
        _testingName.value = host.name
        _testResult.value = null
        viewModelScope.launch {
            // `finally`, not a trailing assignment: if the scope is cancelled (the user leaves
            // the page mid-test) the spinner must not be left running forever — the guard above
            // would then refuse every later test on every host.
            try {
                val result = runCatching { performHandshake(host) }
                    .getOrElse { SshTestResult(false, "connect_failed", it.message) }
                _testResult.value = host.name to result
            } finally {
                _testingName.value = null
            }
        }
    }

    private suspend fun performHandshake(host: SshHostEntity): SshTestResult =
        withContext(Dispatchers.IO) {
            val auth = SshAuth(
                password = host.password,
                privateKey = host.privateKey,
                passphrase = host.passphrase,
            )
            if (!auth.isUsable()) {
                return@withContext SshTestResult(false, "no_credentials", null)
            }
            val jsch = newJSch(context)
            // Same candidate transports the exec path walks, so "test" agrees with
            // `ssh_exec` / `ssh_exec_saved` on the very same host: a factory pinned to the
            // default route alone cannot fall back to a physical network when the default
            // route (an active VPN, adaptive routing, a captive portal) is the broken one, and
            // the page then reported connect_failed for a host the tools reached fine.
            val factory = networkRaceSocketFactory(context)
            var session: Session? = null
            try {
                // [openSshSession] performs the connect. Do NOT add a second `Session.connect`
                // after it: JSch throws "session is already connected" (a JSchException that
                // reads like a network failure) and the page rendered that as a bogus
                // "connect_failed" even though the handshake had just succeeded.
                session = openSshSession(
                    jsch, host.host, host.port, host.user, auth, TEST_TIMEOUT_MS, factory,
                )
                SshTestResult(ok = true)
            } catch (e: Throwable) {
                if (factory.winningLabel == null && factory.failures.isNotEmpty()) {
                    // No transport reached the host at all: report what we tried, the same way
                    // the exec path does, instead of a bare "connect_failed".
                    SshTestResult(
                        ok = false,
                        errorCode = "tcp_unreachable",
                        detail = factory.failures.joinToString("; ") { (label, reason) ->
                            "$label: $reason"
                        },
                    )
                } else {
                    val env = wrapConnectError(host.host, e)
                    SshTestResult(
                        ok = false,
                        errorCode = env["error"]?.jsonPrimitive?.contentOrNull,
                        detail = env["raw"]?.jsonPrimitive?.contentOrNull
                            ?: env["reason"]?.jsonPrimitive?.contentOrNull,
                    )
                }
            } finally {
                runCatching { session?.disconnect() }
            }
        }

    private companion object {
        /**
         * Deliberately generous — the same ceiling `ssh_exec` uses by default. A test that gives
         * up sooner than the tool it stands in for would report failures the tool never had: the
         * first byte through an active VPN can take several seconds longer than over a LAN.
         */
        const val TEST_TIMEOUT_MS = 30_000
    }
}
