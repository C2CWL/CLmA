package com.clma.app.ui.components.ui

/** One work indicator owns one animator; updates must not restart it, disposal is terminal. */
internal class WorkAnimationController(
    private val start: () -> Unit,
    private val stop: () -> Unit,
) {
    var running = false
        private set
    private var released = false

    fun update(active: Boolean, visible: Boolean) {
        if (released) return
        val next = active && visible
        if (next == running) return
        running = next
        if (next) start() else stop()
    }

    fun release() {
        if (released) return
        released = true
        if (running) {
            running = false
            stop()
        }
    }
}
