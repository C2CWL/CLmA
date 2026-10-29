package com.clma.app.ui.hooks

/**
 * Pure resync rule behind [rememberSyncedTextField].
 *
 * A text field whose text is persisted asynchronously (ViewModel -> DataStore/Room -> Flow) sees
 * its own writes come back later as "external" values. Those echoes are stale by the time they
 * arrive: while the user types "a", "ab", "abc", the flow may still be emitting "a". If the field
 * were driven by that value, the stale echo would overwrite what the IME is composing, which
 * produces duplicated characters and a cursor stuck at the old length.
 *
 * This class decides, for each new external value, whether it is
 *  - an echo of something we pushed ourselves (ignore, keep the local text and IME state), or
 *  - a genuine change from outside, e.g. a restore or another screen (adopt it).
 *
 * No Android / Compose dependency so it can be unit tested on the JVM.
 */
class ExternalTextSync(initialExternal: String) {
    private var lastExternal: String = initialExternal

    // Echo values we expect to see from persistence, oldest first.
    private val pending = ArrayDeque<String>()

    /** Number of pushed values whose echo has not come back yet (for tests / debugging). */
    val pendingCount: Int get() = pending.size

    /**
     * Records that the local text changed and a value was pushed to persistence.
     * [expectedEcho] is what the external value will look like once that write lands
     * (equal to the text itself unless the caller normalises, e.g. trims or parses a number).
     */
    fun onLocalEdit(expectedEcho: String) {
        if (pending.lastOrNull() == expectedEcho) return
        pending.addLast(expectedEcho)
        while (pending.size > MAX_PENDING) pending.removeFirst()
    }

    /**
     * Called whenever the external (persisted) value is observed.
     *
     * @return the text the field should switch to, or `null` to keep the local text untouched.
     */
    fun onExternal(external: String, localText: String): String? {
        if (external == lastExternal) return null
        lastExternal = external

        val index = pending.indexOf(external)
        if (index >= 0) {
            // Echo of one of our own writes (matched oldest first, so typing "a", "ab" and
            // deleting back to "a" survives the "ab" echo that is still in flight).
            repeat(index + 1) { pending.removeFirst() }
            return null
        }
        if (external == localText) {
            // Persistence matches what is on screen.
            pending.clear()
            return null
        }
        // Changed from outside: take it.
        pending.clear()
        return external
    }

    companion object {
        const val MAX_PENDING = 64
    }
}
