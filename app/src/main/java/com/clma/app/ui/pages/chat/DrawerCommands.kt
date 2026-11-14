package com.clma.app.ui.pages.chat

import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Main-thread latest-intent owner. Material still owns gestures, anchors, velocity and specs. */
internal class DrawerCommands(
    private val scope: CoroutineScope,
    private val animate: suspend (DrawerValue) -> Unit,
) {
    private var generation = 0L
    private var pending: DrawerValue? = null
    private var job: Job? = null
    private var disposed = false

    fun request(target: DrawerValue) {
        if (disposed || (job?.isActive == true && pending == target)) return
        val token = ++generation
        job?.cancel()
        pending = target
        val next = scope.launch(start = CoroutineStart.LAZY) {
            try {
                animate(target)
            } finally {
                // A canceled old animation must not clear ownership of its replacement.
                if (generation == token) {
                    pending = null
                    job = null
                }
            }
        }
        job = next
        next.start()
    }

    fun open() = request(DrawerValue.Open)
    fun close() = request(DrawerValue.Closed)

    fun dispose() {
        if (disposed) return
        disposed = true
        generation++
        job?.cancel()
        job = null
        pending = null
    }
}

@Composable
internal fun rememberDrawerCommands(drawer: DrawerState): DrawerCommands {
    val scope = rememberCoroutineScope()
    val commands = remember(drawer, scope) {
        DrawerCommands(scope) { target ->
            if (target == DrawerValue.Open) drawer.open() else drawer.close()
        }
    }
    DisposableEffect(commands) { onDispose { commands.dispose() } }
    return commands
}

internal fun drawerBackEnabled(current: DrawerValue, target: DrawerValue, running: Boolean): Boolean =
    running || current == DrawerValue.Open || target == DrawerValue.Open
