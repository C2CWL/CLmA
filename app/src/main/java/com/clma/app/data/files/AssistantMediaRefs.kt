package com.clma.app.data.files

import com.clma.app.data.model.Assistant
import com.clma.app.data.model.Avatar
import java.io.File
import java.net.URI

/**
 * 解析「助手外观文件」—— 头像与聊天壁纸。
 *
 * 这些图片和普通聊天附件一样，都是通过 `createChatFilesByContents()` 拷进 `filesDir/upload`
 * 的，因此「清理上传目录」会把它们一并删掉，助手头像/壁纸随即变成裂图。文件管理页用这里的
 * 结果把这类文件单独分组，[FilesManager.deleteAll] / [FilesManager.deleteOlderThan] 也会跳过
 * 这些路径。
 *
 * 判定只认 `file:` URI：头像是 URL 形式的（`Avatar.Image("https://…")`）或者壁纸是网络地址时，
 * 磁盘上并没有对应文件，不在保护范围内。
 */
object AssistantMediaRefs {
    enum class Role { AVATAR, BACKGROUND }

    /** 相对 `filesDir` 的路径 → 用途。同一文件既是头像又是壁纸时保留 [Role.AVATAR]。 */
    fun protectedFiles(
        assistants: List<Assistant>,
        filesDir: File,
        /** 用户自己的头像（`DisplaySetting.userAvatar`）同样落在 upload 目录里。 */
        userAvatar: Avatar? = null,
    ): Map<String, Role> {
        val result = linkedMapOf<String, Role>()
        (userAvatar as? Avatar.Image)?.url?.let { record(result, it, Role.AVATAR, filesDir) }
        assistants.forEach { assistant ->
            (assistant.avatar as? Avatar.Image)?.url?.let { record(result, it, Role.AVATAR, filesDir) }
            listOf(assistant.background, assistant.darkBackground, assistant.lightBackground)
                .forEach { record(result, it, Role.BACKGROUND, filesDir) }
        }
        return result
    }

    fun protectedRelativePaths(
        assistants: List<Assistant>,
        filesDir: File,
        userAvatar: Avatar? = null,
    ): Set<String> = protectedFiles(assistants, filesDir, userAvatar).keys

    private fun record(
        target: MutableMap<String, Role>,
        raw: String?,
        role: Role,
        filesDir: File,
    ) {
        val relativePath = relativePathOf(raw, filesDir) ?: return
        val current = target[relativePath]
        if (current == null || (current == Role.BACKGROUND && role == Role.AVATAR)) {
            target[relativePath] = role
        }
    }

    internal fun relativePathOf(raw: String?, filesDir: File): String? {
        val path = localFilePathOf(raw) ?: return null
        return FileUtils.getRelativePathInFilesDir(filesDir, File(path))
    }

    /** `file:` URI → 绝对路径；非 file 协议、空值、解析失败一律返回 null。 */
    internal fun localFilePathOf(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        if (!uri.scheme.equals("file", ignoreCase = true)) return null
        return uri.path?.takeIf { it.isNotEmpty() }
    }
}
