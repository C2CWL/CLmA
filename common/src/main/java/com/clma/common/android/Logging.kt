package com.clma.common.android

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MAX_RECENT_LOGS = 300

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

    // "Record requests" switch. Defaults to true to match Settings.recordRequests; :app
    // restores the user's saved value from SettingsStore at startup (RikkaHubApp.onCreate),
    // so this initial value only applies until settings have loaded.
    @Volatile
    private var requestLoggingEnabled = true

    // Modules below :app (e.g. :ai) have no BuildConfig of their own to gate verbose
    // android.util.Log calls with, the way :app already gates HttpLoggingInterceptor
    // behind BuildConfig.DEBUG. RikkaHubApp.onCreate() sets this once from
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
    private const val MAX_FILE_BYTES = 1_000_000L
    private const val MAX_ROTATED_FILES = 2

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val writeLock = Any()

    /** Point the rolling file log at [dir]. Safe to call more than once. */
    fun init(dir: File, minLevel: LogLevel = LogLevel.INFO) {
        logDir = dir
        fileLogLevel = minLevel
        try {
            if (!dir.exists()) dir.mkdirs()
        } catch (_: IOException) {
            // Storage problems must never crash logging itself.
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
        }
        if (entry.level >= fileLogLevel) {
            appendToFile(entry)
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

    fun clear() {
        synchronized(recentLogs) {
            recentLogs.clear()
        }
        runCatching { logDir?.let { File(it, LOG_FILE_NAME).takeIf(File::exists)?.delete() } }
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