package com.clma.app.utils

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import android.util.Log
import androidx.core.content.edit
import com.clma.common.android.Logging

private const val TAG = "CrashHandler"
private const val PREFS_NAME = "crash_handler"
private const val KEY_CRASHED = "crashed"
private const val KEY_STACKTRACE = "stacktrace"
private const val KEY_LAST_EXIT_TIMESTAMP = "last_exit_timestamp"
private const val MAX_STACKTRACE_LENGTH = 8000

object CrashHandler {
    fun install(context: Context) {
        val appContext = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                recoverNativeCrash(appContext)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot read historical process exits", e)
            }
        }
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e(TAG, "Uncaught exception on thread ${thread.name}", throwable)
            } catch (_: Throwable) {
            }
            try {
                markCrashed(appContext, thread, throwable)
            } catch (_: Throwable) {
                // Never let crash bookkeeping replace the original crash.
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun recoverNativeCrash(context: Context) {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        // WebView and helper processes share the package but must not trigger app recovery.
        val exit = manager.getHistoricalProcessExitReasons(context.packageName, 0, 0)
            .filter { it.processName == context.packageName }
            .maxByOrNull { it.timestamp } ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastSeen = prefs.getLong(KEY_LAST_EXIT_TIMESTAMP, 0L)
        if (exit.timestamp <= lastSeen) return
        val nativeCrash = shouldRecoverNativeCrash(
            exit.timestamp, lastSeen, exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE,
        )
        val report = if (nativeCrash) {
            buildString {
                appendLine("Native process crash (SIGSEGV/SIGABRT)")
                appendLine("Process: ${exit.processName}")
                appendLine("Timestamp: ${exit.timestamp}")
                appendLine("Status: ${exit.status}")
                appendLine("Description: ${exit.description.orEmpty()}")
                appendLine("Native tombstone is available through Android crash logs.")
            }.take(MAX_STACKTRACE_LENGTH)
        } else null
        // Consume each exit once, including normal stops, so an old crash cannot loop Safe Mode.
        prefs.edit(commit = true) {
            putLong(KEY_LAST_EXIT_TIMESTAMP, exit.timestamp)
            if (report != null) {
                putBoolean(KEY_CRASHED, true)
                putString(KEY_STACKTRACE, report)
            }
        }
        if (report != null) Logging.logCrash(TAG, report)
    }

    fun hasCrashed(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CRASHED, false)
    }

    fun getStackTrace(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_STACKTRACE, null)
    }

    fun clearCrashed(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { remove(KEY_CRASHED).remove(KEY_STACKTRACE) }
    }

    private fun markCrashed(context: Context, thread: Thread, throwable: Throwable) {
        val stackTrace = buildString {
            appendLine("Thread: ${thread.name}")
            appendLine(throwable.stackTraceToString())
        }.take(MAX_STACKTRACE_LENGTH)
        // Also into the on-disk app.log first (synchronous, bounded, never throws), so
        // "复制全部日志" still has the stack even after Safe Mode consumed the prefs copy.
        Logging.logCrash(TAG, stackTrace)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit(commit = true) {
                putBoolean(KEY_CRASHED, true)
                putString(KEY_STACKTRACE, stackTrace)
            } // commit() 同步写入，确保崩溃前写完
    }
}
