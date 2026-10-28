package com.clma.app.browser

/** Monotonic task budget, deliberately independent of navigation and page lifetime. */
class BrowserTaskClock(private val now: () -> Long) {
    var state: String = "idle"
        private set
    var deadline: Long = 0
        private set
    fun start(budgetMs: Long) {
        if (state == "idle" || state == "done") {
            deadline = now() + budgetMs.coerceIn(60_000L, 3_600_000L)
            state = "running"
        }
    }
    fun status(): String {
        if (state == "running" && now() >= deadline) state = "timed_out"
        return state
    }
    fun allowed() = status() in setOf("idle", "running", "done")
    fun finish() { state = "done" }
    fun cancel() { state = "cancelled" }
    fun extend(minutes: Int) {
        require(minutes in 1..60)
        deadline = now() + minutes * 60_000L
        state = "running"
    }
}
