package me.rerere.rikkahub.service

import java.io.File
import kotlin.random.Random

/**
 * Files behind the user-picked floating-ball icon, in the app's private files dir.
 *
 * Every pick gets a fresh, timestamped name. Re-using one fixed name left
 * `floatingBallIconPath` unchanged, so the de-duplicated settings flow never emitted and the
 * running ball kept painting the previous picture until something else changed.
 */
internal object FloatingBallIconFiles {
    /** Name used before picks were timestamped; still cleaned up. */
    const val LEGACY_NAME = "floating_ball_icon"
    private const val PREFIX = "floating_ball_icon_"

    /** `<millis>_<8 hex>` after [PREFIX]; `<millis>` alone is an earlier build's name, still ours. */
    private val STAMP = Regex("""\d+(_[0-9a-f]{8})?""")

    /**
     * A new target for the picture picked at [nowMs], `floating_ball_icon_<millis>_<8 hex>`: the
     * random suffix rules out a collision between two picks in the same millisecond.
     */
    fun newFile(dir: File, nowMs: Long, random: Random = Random.Default): File {
        val suffix = random.nextInt().toUInt().toString(16).padStart(8, '0')
        return File(dir, "$PREFIX${nowMs}_$suffix")
    }

    /** Whether [file] is one of ours (current naming or the legacy fixed name). */
    fun isIconFile(file: File): Boolean =
        file.name == LEGACY_NAME ||
            (file.name.startsWith(PREFIX) && STAMP.matches(file.name.removePrefix(PREFIX)))

    /** Every icon file in [dir] except [keep] (null deletes them all). */
    fun staleFiles(dir: File, keep: File?): List<File> =
        dir.listFiles().orEmpty().filter { it.isFile && isIconFile(it) && it.absolutePath != keep?.absolutePath }

    /** Deletes [staleFiles]; best effort. */
    fun deleteStale(dir: File, keep: File?) {
        staleFiles(dir, keep).forEach { runCatching { it.delete() } }
    }
}
