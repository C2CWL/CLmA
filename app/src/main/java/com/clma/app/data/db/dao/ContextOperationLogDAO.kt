package com.clma.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.clma.app.data.db.entity.ContextOperationLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ContextOperationLogDAO {
    @Insert
    suspend fun insert(log: ContextOperationLogEntity)

    /** Newest first, for the global "compression & clear history" page. */
    @Query("SELECT * FROM context_operation_log ORDER BY created_at DESC")
    fun observeAll(): Flow<List<ContextOperationLogEntity>>

    @Query("SELECT * FROM context_operation_log WHERE conversation_id = :conversationId ORDER BY created_at DESC")
    fun observeByConversation(conversationId: String): Flow<List<ContextOperationLogEntity>>

    @Query("DELETE FROM context_operation_log")
    suspend fun deleteAll()
}
