package me.rerere.rikkahub.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.GenMediaEntity

@Dao
interface GenMediaDAO {
    @Query("SELECT * FROM genmediaentity ORDER BY create_at DESC")
    fun getAll(): PagingSource<Int, GenMediaEntity>

    @Query("SELECT * FROM genmediaentity ORDER BY create_at DESC")
    suspend fun getAllMedia(): List<GenMediaEntity>

    /** Paged rows of one media [type] (`image_generation` / `image_edit` / `video_generation`). */
    @Query("SELECT * FROM genmediaentity WHERE type = :type ORDER BY create_at DESC")
    fun getByType(type: String): PagingSource<Int, GenMediaEntity>

    /** Every row of one media [type], for the orphan purge. */
    @Query("SELECT * FROM genmediaentity WHERE type = :type ORDER BY create_at DESC")
    suspend fun getAllByType(type: String): List<GenMediaEntity>

    @Insert
    suspend fun insert(media: GenMediaEntity)

    @Query("DELETE FROM genmediaentity WHERE id = :id")
    suspend fun delete(id: Int)
}
