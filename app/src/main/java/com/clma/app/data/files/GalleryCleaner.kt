package com.clma.app.data.files

import android.content.Context
import com.clma.app.data.db.entity.GenMediaEntity
import com.clma.app.data.repository.GenMediaRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 清理生成图片（`filesDir/images` + [GenMediaEntity] 记录）。
 *
 * 生成图片不走 ManagedFile 体系：文件在 `filesDir/images` 下，画廊靠 `genmediaentity` 表里的
 * 相对路径取用。所以删文件必须连记录一起删，否则画廊会留下一堆点不开的空白项；反过来只删
 * 记录也不行，磁盘占用不会降下来。这里把两件事绑在一次操作里。
 */
class GalleryCleaner(
    private val context: Context,
    private val genMediaRepository: GenMediaRepository,
) {
    companion object {
        /** 只处理图片；视频在 `filesDir/videos`，不在「图片清理」的范围内。 */
        private val IMAGE_TYPES = setOf(
            GenMediaEntity.TYPE_IMAGE_GENERATION,
            GenMediaEntity.TYPE_IMAGE_EDIT,
        )
    }

    suspend fun listImages(): List<GenMediaEntity> =
        genMediaRepository.getAllMediaList().filter { it.type in IMAGE_TYPES }

    suspend fun deleteOlderThan(cutoffMillis: Long): Boolean = withContext(Dispatchers.IO) {
        deleteMatching { it.createAt < cutoffMillis }
    }

    suspend fun deleteAll(): Boolean = withContext(Dispatchers.IO) {
        deleteMatching { true }
    }

    /** 删单张：文件 + 画廊记录一起删。 */
    suspend fun delete(media: GenMediaEntity): Boolean = withContext(Dispatchers.IO) {
        val file = resolve(media.path)
        val deleted = file == null || !file.exists() ||
            runCatching { file.delete() }.getOrDefault(false)
        if (deleted) {
            genMediaRepository.deleteMedia(media.id)
        }
        deleted
    }

    private suspend fun deleteMatching(predicate: (GenMediaEntity) -> Boolean): Boolean {
        var allDeleted = true
        genMediaRepository.getAllMediaList()
            .filter { it.type in IMAGE_TYPES && predicate(it) }
            .forEach { entity ->
                val file = resolve(entity.path)
                val deleted = file == null || !file.exists() ||
                    runCatching { file.delete() }.getOrDefault(false)
                if (deleted) {
                    genMediaRepository.deleteMedia(entity.id)
                } else {
                    allDeleted = false
                }
            }
        return allDeleted
    }

    /** `path` 是相对 filesDir 的（`images/xxx.png`）；`../` 之类的越界路径直接判为不可用。 */
    private fun resolve(path: String): File? {
        val candidate = File(context.filesDir, path)
        return candidate.takeIf { FileUtils.getRelativePathInFilesDir(context.filesDir, it) != null }
    }
}
