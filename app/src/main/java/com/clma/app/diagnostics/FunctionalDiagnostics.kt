package com.clma.app.diagnostics

import android.content.Context
import android.os.SystemClock
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

    @Volatile var functionsEnabled = false
        private set

    @Volatile var animationEnabled = false
        private set
    private val queue = Channel<Pair<String, LogLevel>>(256)
    private val dropped = AtomicLong()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        functionsEnabled = context.readBooleanPreference(FUNCTION_KEY)
        animationEnabled = context.readBooleanPreference(ANIMATION_KEY)
        scope.launch {
            for ((message, level) in queue) {
                val lost = dropped.getAndSet(0)
                if (lost > 0) Logging.log("FunctionalDiag", "dropped=$lost", LogLevel.WARN)
                Logging.log("FunctionalDiag", message, level)
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

    private fun emit(
        message: String,
        level: LogLevel,
    ) {
        if (queue.trySend("t=${SystemClock.elapsedRealtime()} $message" to level).isFailure) {
            dropped.incrementAndGet()
        }
    }
}
