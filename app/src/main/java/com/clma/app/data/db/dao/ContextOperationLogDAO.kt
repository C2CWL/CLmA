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
    @Query("""
        SELECT log.* FROM context_operation_log log JOIN conversationentity c ON c.id = log.conversation_id
        WHERE (:allowAll OR c.assistant_id IN (:include)) AND c.assistant_id NOT IN (:exclude)
        ORDER BY log.created_at DESC
    """)
    fun observeAll(allowAll: Boolean, include: List<String>, exclude: List<String>): Flow<List<ContextOperationLogEntity>>

    @Query("SELECT * FROM context_operation_log WHERE conversation_id = :conversationId ORDER BY created_at DESC")
    fun observeByConversation(conversationId: String): Flow<List<ContextOperationLogEntity>>

    @Query("""
        DELETE FROM context_operation_log WHERE conversation_id IN (
            SELECT id FROM conversationentity
            WHERE (:allowAll OR assistant_id IN (:include)) AND assistant_id NOT IN (:exclude)
        )
    """)
    suspend fun deleteAll(allowAll: Boolean, include: List<String>, exclude: List<String>)
}
