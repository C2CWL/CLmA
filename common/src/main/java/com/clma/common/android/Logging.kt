package com.clma.common.android

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MAX_RECENT_LOGS = 300

/**
 * Upper bound (in chars) on request bodies kept by the in-memory ring buffer, summed over all
 * entries. Agent turns resend the whole history on every step, so 300 full prompts near the
 * model's context limit add up to hundreds of MB and ran the app out of memory. Once the
 * budget is spent, older entries keep their metadata but lose the body.
 */
internal const val MAX_IN_MEMORY_BODY_CHARS = 2_000_000

/** How loud a log line is. Kept small on purpose — users only ever see three colours. */
enum class LogLevel { DEBUG, INFO, WARN, ERROR }

@Serializable
sealed class LogEntry {
    abstract val id: Uuid
    abstract val timestamp: Long
    abstract val tag: String
    abstract val level: LogLevel

    @Serializable
    data class TextLog(
        override val id: Uuid = Uuid.random(),
        override val timestamp: Long = System.currentTimeMillis(),
        override val tag: String,
        val message: String,
        override val level: LogLevel = LogLevel.INFO,
    ) : LogEntry()

    @Serializable
    data class RequestLog(
        override val id: Uuid = Uuid.random(),
        override val timestamp: Long = System.currentTimeMillis(),
        override val tag: String,
        val url: String,
        val method: String,
        val requestHeaders: Map<String, String> = emptyMap(),
        val requestBody: String? = null,
        val responseCode: Int? = null,
        val responseHeaders: Map<String, String> = emptyMap(),
        val durationMs: Long? = null,
        val error: String? = null,
        override val level: LogLevel = LogLevel.INFO,
    ) : LogEntry()
}

/**
 * In-memory log ring buffer plus an optional rolling on-disk copy.
 *
 * Why both: the in-memory list is what the Logs screen renders (cheap, no I/O), while the file
 * is what survives a process kill — the usual reason a user opens the Logs screen is that the
 * app just died or a request failed, and by then the ring buffer is already gone.
 *
 * Call [init] once from `Application.onCreate`. Without it the object behaves exactly as before
 * (memory only), so unit tests and other modules keep working.
 */
object Logging {
    private val recentLogs = arrayListOf<LogEntry>()

    private val _version = MutableStateFlow(0L)

    /**
     * Bumped every time the in-memory list changes (new entry or [clear]). The Logs screen
     * collects this and re-reads [getRecentLogs], so it updates live. A counter instead of a
     * `StateFlow<List<LogEntry>>` keeps [addLog] cheap: no list copy per log line, and the
     * copy only happens when someone is actually looking.
     */
    val version: StateFlow<Long> = _version.asStateFlow()

    // "Record requests" switch. Defaults to true to match Settings.recordRequests; :app
    // restores the user's saved value from SettingsStore at startup (ClmaApp.onCreate),
    // so this initial value only applies until settings have loaded.
    @Volatile
    private var requestLoggingEnabled = true

    // Modules below :app (e.g. :ai) have no BuildConfig of their own to gate verbose
    // android.util.Log calls with, the way :app already gates HttpLoggingInterceptor
    // behind BuildConfig.DEBUG. ClmaApp.onCreate() sets this once from
    // BuildConfig.DEBUG so provider code can gate full request/response body logging
    // (which otherwise carries prompts and tool results into logcat on every release
    // build too) without a per-module build.gradle change.
    @Volatile
    private var debugLoggingEnabled = false

    /** Minimum level written to disk. DEBUG lines are dropped in release builds. */
    @Volatile
    private var fileLogLevel = LogLevel.INFO

    @Volatile
    private var logDir: File? = null

    private const val LOG_FILE_NAME = "app.log"
    private const val MAX_CRASH_CHARS = 8_000
    private const val MAX_FILE_BYTES = 1_000_000L
    private const val MAX_ROTATED_FILES = 2

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val writeLock = Any()

    // Where this process's log lines start on disk, so the Logs screen can show older sessions
    // without repeating what is already in memory. Both guarded by [writeLock].
    // - sessionStartOffset: length of app.log when [init] ran; bytes before it are older.
    // - rotationsThisSession: how often app.log was rotated since then, i.e. which file now
    //   holds the session start (0 = app.log, 1 = app.log.1, ...).
    private var sessionStartOffset = 0L
    private var rotationsThisSession = 0

    /** Point the rolling file log at [dir]. Safe to call more than once. */
    fun init(dir: File, minLevel: LogLevel = LogLevel.INFO) {
        logDir = dir
        fileLogLevel = minLevel
        try {
            if (!dir.exists()) dir.mkdirs()
        } catch (_: IOException) {
            // Storage problems must never crash logging itself.
        }
        synchronized(writeLock) {
            // Only a stat, no read: init runs on the main thread in Application.onCreate.
            sessionStartOffset = File(dir, LOG_FILE_NAME).length()
            rotationsThisSession = 0
        }
    }

    fun log(tag: String, message: String, level: LogLevel = LogLevel.INFO) {
        addLog(LogEntry.TextLog(tag = tag, message = message, level = level))
    }

    /**
     * [force] bypasses the "record requests" switch. Failed requests always pass through it:
     * the switch exists to avoid hoarding every successful call, not to hide the one that broke.
     */
    fun logRequest(entry: LogEntry.RequestLog, force: Boolean = false) {
        if (!force && !requestLoggingEnabled) return
        addLog(entry)
    }

    fun isRequestLoggingEnabled(): Boolean = requestLoggingEnabled

    fun setRequestLoggingEnabled(enabled: Boolean) {
        requestLoggingEnabled = enabled
    }

    fun isDebugLoggingEnabled(): Boolean = debugLoggingEnabled

    fun setDebugLoggingEnabled(enabled: Boolean) {
        debugLoggingEnabled = enabled
    }

    private fun addLog(entry: LogEntry) {
        synchronized(recentLogs) {
            recentLogs.add(0, entry)
            if (recentLogs.size > MAX_RECENT_LOGS) {
                recentLogs.removeLastOrNull()
            }
            enforceBodyBudget()
        }
        _version.update { it + 1 }
        if (entry.level >= fileLogLevel) {
            appendToFile(entry)
        }
    }

    /** Strips bodies of older request entries once the newest ones use up the budget. Caller holds the lock. */
    private fun enforceBodyBudget() {
        var used = 0L
        for (index in recentLogs.indices) {
            val entry = recentLogs[index] as? LogEntry.RequestLog ?: continue
            val body = entry.requestBody ?: continue
            if (used + body.length <= MAX_IN_MEMORY_BODY_CHARS) {
                used += body.length
            } else {
                recentLogs[index] = entry.copy(requestBody = omittedBodyPlaceholder(body.length.toLong()))
            }
        }
    }

    /** Placeholder stored instead of a body that was too large to keep. */
    fun omittedBodyPlaceholder(size: Long): String = "<omitted $size bytes>"

    /**
     * Records an uncaught crash in both the ring buffer and the on-disk log, so "copy all logs"
     * still carries the stack after the process dies. Called from the uncaught-exception
     * handler, possibly while out of memory: bounded, synchronous, and never throws.
     */
    fun logCrash(tag: String, stackTrace: String) {
        try {
            addLog(
                LogEntry.TextLog(
                    tag = tag,
                    message = "CRASH\n" + stackTrace.take(MAX_CRASH_CHARS),
                    level = LogLevel.ERROR,
                )
            )
        } catch (_: Throwable) {
            // Logging must never mask the original crash.
        }
    }

    fun getRecentLogs(): List<LogEntry> {
        synchronized(recentLogs) {
            return recentLogs.toList()
        }
    }

    fun getTextLogs(): List<LogEntry.TextLog> {
        synchronized(recentLogs) {
            return recentLogs.filterIsInstance<LogEntry.TextLog>()
        }
    }

    fun getRequestLogs(): List<LogEntry.RequestLog> {
        synchronized(recentLogs) {
            return recentLogs.filterIsInstance<LogEntry.RequestLog>()
        }
    }

    /** Everything we have, newest first, formatted for the clipboard / bug reports. */
    fun exportAsText(): String {
        val builder = StringBuilder()
        val file = logDir?.let { File(it, LOG_FILE_NAME) }
        if (file != null && file.exists()) {
            builder.appendLine("# On-disk log (oldest first)")
            runCatching { builder.append(file.readText()) }
        }
        builder.appendLine()
        builder.appendLine("# In-memory log (newest first)")
        getRecentLogs().forEach { builder.appendLine(format(it)) }
        return builder.toString()
    }

    /**
     * On-disk lines written by earlier app sessions (before the last [init]), newest first.
     * Lines from the current session are left out: they are already in [getRecentLogs].
     * Lines on disk were redacted when written, so nothing extra is needed here.
     *
     * Does file I/O (up to a few MB): call it off the main thread.
     */
    fun readEarlierSessionLogs(): List<String> {
        val dir = logDir ?: return emptyList()
        val text = StringBuilder()
        synchronized(writeLock) {
            // Oldest file first, so the text ends up oldest-to-newest.
            for (index in MAX_ROTATED_FILES downTo 0) {
                if (index < rotationsThisSession) break // whole file is from this session
                val file = logFile(dir, index)
                if (!file.exists()) continue
                val limit = if (index == rotationsThisSession) sessionStartOffset else Long.MAX_VALUE
                runCatching { text.append(readPrefix(file, limit)) }
            }
        }
        return text.lineSequence().filter { it.isNotBlank() }.toList().asReversed()
    }

    fun clear() {
        synchronized(recentLogs) {
            recentLogs.clear()
        }
        synchronized(writeLock) {
            runCatching {
                logDir?.let { dir ->
                    for (index in 0..MAX_ROTATED_FILES) {
                        logFile(dir, index).takeIf(File::exists)?.delete()
                    }
                }
            }
            sessionStartOffset = 0L
            rotationsThisSession = 0
        }
        _version.update { it + 1 }
    }

    /** Test hook: forget the log directory so other tests see the memory-only default. */
    internal fun resetFileLogForTest() {
        synchronized(writeLock) {
            logDir = null
            fileLogLevel = LogLevel.INFO
            sessionStartOffset = 0L
            rotationsThisSession = 0
        }
    }

    private fun logFile(dir: File, index: Int): File =
        if (index == 0) File(dir, LOG_FILE_NAME) else File(dir, "$LOG_FILE_NAME.$index")

    private fun readPrefix(file: File, limit: Long): String {
        RandomAccessFile(file, "r").use { raf ->
            val size = minOf(raf.length(), limit).toInt()
            val bytes = ByteArray(size)
            raf.readFully(bytes)
            return String(bytes, Charsets.UTF_8)
        }
    }

    private fun format(entry: LogEntry): String {
        // SimpleDateFormat is not thread-safe and log calls come from arbitrary threads.
        val time = synchronized(timeFormat) { timeFormat.format(Date(entry.timestamp)) }
        return when (entry) {
            is LogEntry.TextLog -> "$time ${entry.level} [${entry.tag}] ${entry.message}"
            is LogEntry.RequestLog -> buildString {
                append("$time ${entry.level} [${entry.tag}] ${entry.method} ${redact(entry.url)}")
                entry.responseCode?.let { append(" -> $it") }
                entry.durationMs?.let { append(" (${it}ms)") }
                entry.error?.let { append(" error=${redact(it)}") }
            }
        }
    }

    private fun appendToFile(entry: LogEntry) {
        val dir = logDir ?: return
        try {
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, LOG_FILE_NAME)
            synchronized(writeLock) {
                if (file.length() > MAX_FILE_BYTES) {
                    rotate(dir, file)
                }
                file.appendText(format(entry) + "\n")
            }
        } catch (_: IOException) {
            // Never let logging break the caller.
        }
    }

    private fun rotate(dir: File, file: File) {
        for (index in MAX_ROTATED_FILES downTo 1) {
            val older = File(dir, "$LOG_FILE_NAME.$index")
            if (index == MAX_ROTATED_FILES) {
                older.delete()
            } else {
                val newer = File(dir, "$LOG_FILE_NAME.${index + 1}")
                if (older.exists()) older.renameTo(newer)
            }
        }
        file.renameTo(File(dir, "$LOG_FILE_NAME.1"))
        rotationsThisSession++
    }

    /**
     * Strip credentials before anything hits disk. Logs get copied into bug reports and shared
     * with strangers; an API key leaking through `Authorization: Bearer ...` is the classic way
     * that happens.
     */
    private fun redact(text: String): String = text
        .replace(Regex("""(?i)(bearer\s+)[A-Za-z0-9\-._~+/]{8,}=*"""), "$1***")
        .replace(Regex("""(?i)(sk-[A-Za-z0-9\-_]{8,})"""), "sk-***")
        .replace(Regex("""(?i)((?:api[_-]?key|token|secret|password)\s*[=:]\s*)[^\s&,"']+"""), "$1***")
}