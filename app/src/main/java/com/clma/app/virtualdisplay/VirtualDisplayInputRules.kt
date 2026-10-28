package com.clma.app.virtualdisplay

/** Fail closed on unknown OEM dumps. Never use mCurTokenDisplayId: the keyboard may be on 0. */
internal data class InputTarget(val uid: Int, val displayId: Int)
internal fun parseInputTarget(dump: String): InputTarget? {
    fun client(key: String): InputTarget? {
        val line = dump.lineSequence().filter { it.trimStart().startsWith("$key=") }.singleOrNull() ?: return null
        val state = line.substringAfter("$key=").substringBefore('}')
        if (!state.startsWith("ClientState{")) return null
        val uid = Regex("\\bmUid=(\\d+)\\b").find(state)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val display = Regex("\\bmSelfReportedDisplayId=(\\d+)\\b").find(state)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return InputTarget(uid, display)
    }
    val current = client("mCurClient") ?: return null
    val focusedLine = dump.lineSequence().filter { it.trimStart().startsWith("mCurFocusedWindow=") }.singleOrNull() ?: return null
    val focused = focusedLine.substringAfter(" client=", "")
    val uid = Regex("\\bmUid=(\\d+)\\b").find(focused)?.groupValues?.get(1)?.toIntOrNull() ?: return null
    val display = Regex("\\bmSelfReportedDisplayId=(\\d+)\\b").find(focused)?.groupValues?.get(1)?.toIntOrNull() ?: return null
    return current.takeIf { it == InputTarget(uid, display) && it.displayId > 0 }
}

/** Cubic ease-out followed by stationary samples in the privileged injector. */
internal fun swipeFraction(t: Float): Float {
    val remaining = 1f - t.coerceIn(0f, 1f)
    return 1f - remaining * remaining * remaining
}
