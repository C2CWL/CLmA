package me.rerere.rikkahub.ui.pages.videogen

import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Bare-JVM coverage for the video gallery's orphan purge. */
class VideoGenVMPurgeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun row(id: Int, path: String) = GenMediaEntity(
        id = id,
        path = path,
        modelId = "Wan",
        prompt = "a cat",
        createAt = id.toLong(),
        type = GenMediaEntity.TYPE_VIDEO_GENERATION,
    )

    @Test
    fun `rows whose backing file is missing are orphaned`() {
        val dir = tmp.newFolder("videos")
        File(dir, "a.mp4").writeBytes(byteArrayOf(1))
        val present = row(1, "videos/a.mp4")
        val missing = row(2, "videos/b.mp4")

        assertEquals(listOf(missing), selectOrphanedVideoMedia(listOf(present, missing), dir))
    }

    @Test
    fun `a non-empty gallery with every file present has no orphans`() {
        val dir = tmp.newFolder("videos")
        File(dir, "a.mp4").writeBytes(byteArrayOf(1))
        File(dir, "b.mp4").writeBytes(byteArrayOf(2))

        assertTrue(
            selectOrphanedVideoMedia(
                listOf(row(1, "videos/a.mp4"), row(2, "videos/b.mp4")),
                dir,
            ).isEmpty()
        )
    }
}
