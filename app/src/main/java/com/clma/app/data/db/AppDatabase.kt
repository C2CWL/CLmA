package com.clma.app.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.clma.ai.core.TokenUsage
import com.clma.app.data.agentrun.AgentRun
import com.clma.app.data.agentrun.AgentRunDao
import com.clma.app.data.db.dao.ConversationDAO
import com.clma.app.data.db.dao.ConversationCompactionDAO
import com.clma.app.data.db.dao.FavoriteDAO
import com.clma.app.data.db.dao.FolderDAO
import com.clma.app.data.db.dao.GenMediaDAO
import com.clma.app.data.db.dao.ManagedFileDAO
import com.clma.app.data.db.dao.MemoryDAO
import com.clma.app.data.db.dao.MessageNodeDAO
import com.clma.app.data.db.dao.ScheduledJobDao
import com.clma.app.data.db.dao.ScheduledJobRunDao
import com.clma.app.data.db.dao.SshHostDao
import com.clma.app.data.db.dao.TelegramChatDao
import com.clma.app.data.db.dao.WorkspaceDAO
import com.clma.app.data.db.entity.ConversationEntity
import com.clma.app.data.db.entity.ConversationCompactionEntity
import com.clma.app.data.db.entity.FavoriteEntity
import com.clma.app.data.db.entity.FolderEntity
import com.clma.app.data.db.entity.GenMediaEntity
import com.clma.app.data.db.entity.ManagedFileEntity
import com.clma.app.data.db.entity.MemoryEntity
import com.clma.app.data.db.entity.MessageNodeEntity
import com.clma.app.data.db.entity.ScheduledJobEntity
import com.clma.app.data.db.entity.ScheduledJobRunEntity
import com.clma.app.data.db.entity.SshHostEntity
import com.clma.app.data.db.entity.TelegramChatEntity
import com.clma.app.data.db.entity.WorkspaceEntity
import com.clma.app.data.db.migrations.Migration_16_17
import com.clma.app.data.db.migrations.Migration_20_21
import com.clma.app.data.db.migrations.Migration_21_22
import com.clma.app.data.db.migrations.Migration_22_23
import com.clma.app.data.db.migrations.Migration_8_9
import com.clma.app.utils.JsonInstant
import com.clma.app.workflow.db.WorkflowDao
import com.clma.app.workflow.db.WorkflowEntity
import com.clma.app.workflow.db.WorkflowRunDao
import com.clma.app.workflow.db.WorkflowRunEntity

@Database(
    entities = [
        ConversationEntity::class,
        ConversationCompactionEntity::class,
        MemoryEntity::class,
        GenMediaEntity::class,
        MessageNodeEntity::class,
        ManagedFileEntity::class,
        FavoriteEntity::class,
        ScheduledJobEntity::class,
        ScheduledJobRunEntity::class,
        SshHostEntity::class,
        TelegramChatEntity::class,
        WorkflowEntity::class,
        WorkflowRunEntity::class,
        AgentRun::class,
        WorkspaceEntity::class,
        FolderEntity::class,
    ],
    version = 31,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9, spec = Migration_8_9::class),
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11),
        AutoMigration(from = 12, to = 13),
        AutoMigration(from = 16, to = 17, spec = Migration_16_17::class),
        AutoMigration(from = 17, to = 18),
        AutoMigration(from = 18, to = 19),
        AutoMigration(from = 19, to = 20),
        AutoMigration(from = 20, to = 21, spec = Migration_20_21::class),
        AutoMigration(from = 21, to = 22, spec = Migration_21_22::class),
        AutoMigration(from = 22, to = 23, spec = Migration_22_23::class),
        // v25: upstream 2.2.6 added conversation-level custom_system_prompt / mode_injection_ids
        // / lorebook_ids columns (all carry defaultValue, so a plain auto-migration suffices).
        AutoMigration(from = 24, to = 25),
        // v26: the 2.3.1 merge brings upstream's workspaces table (WorkspaceEntity). Existing
        // fork users never had it, so Room auto-creates the table on this step.
        AutoMigration(from = 25, to = 26),
        // v27: upstream 2.4.x added conversation folders (FolderEntity -> conversation_folder
        // table) plus a folder_id column on ConversationEntity (defaultValue ""). Both are pure
        // additions; upstream numbered it as their v24, folded into the fork's version space here.
        AutoMigration(from = 26, to = 27),
        // v28: indices only. Conversation listing, assistant memory lookup, the enabled-job
        // scan and per-job run history were all full table scans; see each entity for which
        // query shape its index covers. Pure additions, so Room generates the CREATE INDEX
        // statements itself.
        AutoMigration(from = 27, to = 28),
        // v29: the conversation_compaction table backing automatic context compaction. The
        // table is a pure addition and the original message nodes are left untouched, so Room
        // creates it outright. Numbered 29 rather than 28 because the fork's v28 was already
        // taken by the index migration above.
        AutoMigration(from = 28, to = 29),
        // v30: a chat_model_id column on ConversationEntity so subagent_dispatch's model_id
        // override (#28) survives ChatService.initializeConversation reloading the conversation
        // from Room. Nullable-equivalent (empty string default, matching folder_id), so a plain
        // auto-migration suffices.
        AutoMigration(from = 29, to = 30),
        // v31: the 2.5.1 merge brings upstream's workspace "Shell compatibility mode" flag,
        // adding a column to WorkspaceEntity. Hand-written as Migration_30_31 (registered in
        // AppDatabaseFactory) instead of a plain auto-migration: a database restored from an
        // upstream 2.5.x backup can already carry this column, and an unconditional ADD COLUMN
        // crash-loops the app on every launch (issue #105).
    ]
)
@TypeConverters(TokenUsageConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDAO

    abstract fun conversationCompactionDao(): ConversationCompactionDAO

    abstract fun memoryDao(): MemoryDAO

    abstract fun genMediaDao(): GenMediaDAO

    abstract fun messageNodeDao(): MessageNodeDAO

    abstract fun managedFileDao(): ManagedFileDAO

    abstract fun favoriteDao(): FavoriteDAO

    abstract fun scheduledJobDao(): ScheduledJobDao

    abstract fun scheduledJobRunDao(): ScheduledJobRunDao

    abstract fun sshHostDao(): SshHostDao

    abstract fun telegramChatDao(): TelegramChatDao

    abstract fun workflowDao(): WorkflowDao

    abstract fun workflowRunDao(): WorkflowRunDao

    abstract fun agentRunDao(): AgentRunDao

    abstract fun workspaceDao(): WorkspaceDAO

    abstract fun folderDao(): FolderDAO
}

object TokenUsageConverter {
    @TypeConverter
    fun fromTokenUsage(usage: TokenUsage?): String {
        return JsonInstant.encodeToString(usage)
    }

    @TypeConverter
    fun toTokenUsage(usage: String): TokenUsage? {
        return JsonInstant.decodeFromString(usage)
    }
}
