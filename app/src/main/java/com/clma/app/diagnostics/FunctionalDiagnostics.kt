package com.clma.app.diagnostics

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.clma.app.ui.hooks.readBooleanPreference
import com.clma.common.android.LogLevel
import com.clma.common.android.Logging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/** Only fixed event names and numeric state belong here, never content, IDs or exceptions. */
object FunctionalDiagnostics {
    const val FUNCTION_KEY = "functional_diagnostics"
    const val ANIMATION_KEY = "drawer_animation_diagnostics"

    /** Greppable tag for the per-frame drawer flicker probe (in-app log, app.log and logcat). */
    const val FLICKER_TAG = "DrawerFlicker"
    private const val DEFAULT_TAG = "FunctionalDiag"

    @Volatile var functionsEnabled = false
        private set

    @Volatile var animationEnabled = false
        private set
    private val queue = Channel<Triple<String, String, LogLevel>>(256)
    private val dropped = AtomicLong()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        functionsEnabled = context.readBooleanPreference(FUNCTION_KEY)
        animationEnabled = context.readBooleanPreference(ANIMATION_KEY)
        scope.launch {
            for ((tag, message, level) in queue) {
                val lost = dropped.getAndSet(0)
                if (lost > 0) Logging.log(DEFAULT_TAG, "dropped=$lost", LogLevel.WARN)
                Logging.log(tag, message, level)
                // The flicker probe also goes to logcat, one line per row (logcat truncates
                // long entries), for anyone capturing with adb instead of the Logs screen.
                if (tag == FLICKER_TAG) message.lineSequence().forEach { Log.i(FLICKER_TAG, it) }
            }
        }
        event("process.start")
    }

    fun configure(
        functions: Boolean,
        animation: Boolean,
    ) {
        functionsEnabled = functions
        animationEnabled = animation
        emit("config functions=$functions animation=$animation", LogLevel.INFO)
    }

    fun event(
        name: String,
        instance: Int = 0,
        value: Long = 0,
    ) {
        if (functionsEnabled) emit("$name instance=$instance value=$value", LogLevel.INFO)
    }

    fun failure(name: String) {
        if (functionsEnabled) emit(name, LogLevel.WARN)
    }

    fun animation(
        instance: Int,
        state: String,
    ) {
        if (animationEnabled) emit("drawer.sample instance=$instance $state", LogLevel.INFO)
    }

    /**
     * One drawer flicker probe record (usually a whole open/close episode, many lines). Same
     * gate as [animation]; callers batch per episode so the 300-entry ring buffer is not flushed.
     */
    fun flicker(message: String) {
        if (animationEnabled) emit(message, LogLevel.INFO, FLICKER_TAG)
    }

    private fun emit(
        message: String,
        level: LogLevel,
        tag: String = DEFAULT_TAG,
    ) {
        if (queue.trySend(Triple(tag, "t=${SystemClock.elapsedRealtime()} $message", level)).isFailure) {
            dropped.incrementAndGet()
        }
    }
}
