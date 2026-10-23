package com.clma.app.data.files

import com.clma.app.data.model.Assistant
import com.clma.app.data.model.Avatar
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 头像 / 聊天壁纸和普通附件都躺在 `filesDir/upload`，清理时必须能把它们挑出来，
 * 否则「清空上传目录」会顺手删掉助手外观图。这里覆盖识别逻辑的三种来源。
 */
class AssistantMediaRefsTest {

    private fun tempFilesDir(): File = Files.createTempDirectory("files-dir").toFile()

    private fun touch(filesDir: File, relativePath: String): File {
        val file = File(filesDir, relativePath)
        file.parentFile?.mkdirs()
        file.writeText("x")
        return file
    }

    @Test
    fun `avatar and per-theme wallpapers are protected`() {
        val filesDir = tempFilesDir()
        val avatar = touch(filesDir, "upload/avatar.png")
        val background = touch(filesDir, "upload/bg.png")
        val dark = touch(filesDir, "upload/bg_dark.png")
        val light = touch(filesDir, "upload/bg_light.png")
        val attachment = touch(filesDir, "upload/chat-image.png")

        val assistant = Assistant(
            name = "a",
            avatar = Avatar.Image(avatar.toURI().toString()),
            background = background.toURI().toString(),
            darkBackground = dark.toURI().toString(),
            lightBackground = light.toURI().toString(),
        )

        val protected = AssistantMediaRefs.protectedFiles(listOf(assistant), filesDir)

        assertEquals(AssistantMediaRefs.Role.AVATAR, protected["upload/avatar.png"])
        assertEquals(AssistantMediaRefs.Role.BACKGROUND, protected["upload/bg.png"])
        assertEquals(AssistantMediaRefs.Role.BACKGROUND, protected["upload/bg_dark.png"])
        assertEquals(AssistantMediaRefs.Role.BACKGROUND, protected["upload/bg_light.png"])
        assertEquals(4, protected.size)
        assertTrue("upload/chat-image.png" !in protected)
    }

    @Test
    fun `same file used as avatar and wallpaper stays an avatar`() {
        val filesDir = tempFilesDir()
        val shared = touch(filesDir, "upload/shared.png")
        val assistant = Assistant(
            name = "a",
            avatar = Avatar.Image(shared.toURI().toString()),
            darkBackground = shared.toURI().toString(),
        )

        val protected = AssistantMediaRefs.protectedFiles(listOf(assistant), filesDir)

        assertEquals(AssistantMediaRefs.Role.AVATAR, protected["upload/shared.png"])
    }

    @Test
    fun `user avatar is protected too`() {
        val filesDir = tempFilesDir()
        val userAvatar = touch(filesDir, "upload/me.png")

        val protected = AssistantMediaRefs.protectedFiles(
            assistants = emptyList(),
            filesDir = filesDir,
            userAvatar = Avatar.Image(userAvatar.toURI().toString()),
        )

        assertEquals(AssistantMediaRefs.Role.AVATAR, protected["upload/me.png"])
    }

    @Test
    fun `remote urls, emoji avatars and dummy avatars are ignored`() {
        val filesDir = tempFilesDir()
        val assistants = listOf(
            Assistant(
                name = "remote",
                avatar = Avatar.Image("https://example.com/a.png"),
                background = "https://example.com/bg.png",
            ),
            Assistant(name = "emoji", avatar = Avatar.Emoji("🙂")),
            Assistant(name = "dummy", avatar = Avatar.Dummy),
        )

        assertTrue(AssistantMediaRefs.protectedFiles(assistants, filesDir).isEmpty())
        assertNull(AssistantMediaRefs.localFilePathOf("content://media/1"))
        assertNull(AssistantMediaRefs.localFilePathOf("https://example.com/a.png"))
        assertNull(AssistantMediaRefs.localFilePathOf(null))
    }

    @Test
    fun `files outside the files dir are ignored`() {
        val filesDir = tempFilesDir()
        val outside = Files.createTempDirectory("outside").toFile().resolve("stolen.png").apply {
            writeText("x")
        }

        val assistant = Assistant(
            name = "a",
            avatar = Avatar.Image(outside.toURI().toString()),
        )

        assertTrue(AssistantMediaRefs.protectedFiles(listOf(assistant), filesDir).isEmpty())
    }
}