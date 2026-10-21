package me.rerere.rikkahub.data.sync

import java.io.File
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalUriRemapTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val filesDir: File get() = temp.root

    // --- relativePathInFiles: which references are ours to rewrite ---

    @Test
    fun `extracts the relative path from a foreign package reference`() {
        assertEquals(
            "upload/avatar.jpg",
            LocalUriRemap.relativePathInFiles(
                "file:///data/user/0/excp.rikkahub.debug/files/upload/avatar.jpg"
            ),
        )
    }

    @Test
    fun `ignores remote urls and content uris`() {
        assertNull(LocalUriRemap.relativePathInFiles("https://example.com/a.png"))
        assertNull(LocalUriRemap.relativePathInFiles("content://media/external/images/1"))
        assertNull(LocalUriRemap.relativePathInFiles(null))
    }

    @Test
    fun `ignores file uris that are not inside a files dir`() {
        assertNull(LocalUriRemap.relativePathInFiles("file:///sdcard/Download/a.png"))
    }

    // --- resolve: pass-through vs degrade ---

    @Test
    fun `a missing file degrades to null`() {
        assertNull(
            LocalUriRemap.resolve(
                "file:///data/user/0/other.package/files/upload/gone.jpg",
                filesDir,
            )
        )
    }

    @Test
    fun `null and non-local values pass through untouched`() {
        assertNull(LocalUriRemap.resolve(null, filesDir))
        assertEquals(
            "https://example.com/a.png",
            LocalUriRemap.resolve("https://example.com/a.png", filesDir),
        )
    }

    // --- apply: an assistant whose image is gone loses the dangling reference ---

    @Test
    fun `an assistant whose avatar file is missing falls back to dummy`() {
        val settings = Settings.dummy().copy(
            assistants = listOf(
                Assistant(
                    avatar = Avatar.Image("file:///data/user/0/other.package/files/upload/gone.jpg"),
                    background = "file:///data/user/0/other.package/files/upload/gone-bg.jpg",
                    darkBackground = "file:///data/user/0/other.package/files/upload/gone-dark.jpg",
                    lightBackground = "file:///data/user/0/other.package/files/upload/gone-light.jpg",
                )
            )
        )

        val remapped = LocalUriRemap.apply(settings, filesDir).assistants.single()

        assertEquals(Avatar.Dummy, remapped.avatar)
        assertNull(remapped.background)
        assertNull(remapped.darkBackground)
        assertNull(remapped.lightBackground)
    }

    @Test
    fun `an emoji avatar survives untouched`() {
        val settings = Settings.dummy().copy(
            assistants = listOf(Assistant(avatar = Avatar.Emoji("🐱")))
        )

        assertEquals(Avatar.Emoji("🐱"), LocalUriRemap.apply(settings, filesDir).assistants.single().avatar)
    }
}
