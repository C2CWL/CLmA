package me.rerere.rikkahub.data.repository

import androidx.paging.PagingSource
import me.rerere.rikkahub.data.db.dao.GenMediaDAO
import me.rerere.rikkahub.data.db.entity.GenMediaEntity

class GenMediaRepository(private val dao: GenMediaDAO) {
    fun getAllMedia(): PagingSource<Int, GenMediaEntity> = dao.getAll()

    suspend fun getAllMediaList(): List<GenMediaEntity> = dao.getAllMedia()

    /** Paged gallery rows for one media [type] (e.g. [GenMediaEntity.TYPE_VIDEO_GENERATION]). */
    fun getMediaByType(type: String): PagingSource<Int, GenMediaEntity> = dao.getByType(type)

    /** Every row of one media [type], for orphan purges. */
    suspend fun getAllMediaByType(type: String): List<GenMediaEntity> = dao.getAllByType(type)

    suspend fun insertMedia(media: GenMediaEntity) = dao.insert(media)

    suspend fun deleteMedia(id: Int) = dao.delete(id)
}
