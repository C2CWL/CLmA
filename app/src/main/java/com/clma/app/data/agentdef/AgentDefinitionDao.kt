package me.rerere.rikkahub.data.agentdef

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * P2-06 — CRUD for the expert library. The table is tiny (tens of rows), so every read is a
 * full scan and there is no paging: a definition is small and the UI lists all of them.
 *
 * Name lookups are **case-insensitive** (`COLLATE NOCASE`) because that is exactly how
 * [AgentDefinitionResolver] matches a dispatch's `agent` argument, and the two must agree on
 * what "the same name" means — otherwise the resolver could see two matches from a query that
 * the UI considers a single entry.
 *
 * No parameter has a Kotlin default value: Room generates the implementation of these methods,
 * and a default argument would be resolved through the interface's `$default` synthetic, which
 * the generated override does not provide. Callers pass [AgentDefinitionDefaults.QUERY_LIMIT]
 * explicitly instead.
 */
@Dao
interface AgentDefinitionDao {

    @Query("SELECT * FROM agent_definitions ORDER BY name COLLATE NOCASE ASC LIMIT :limit")
    suspend fun listAll(limit: Int): List<AgentDefinition>

    @Query("SELECT * FROM agent_definitions WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): AgentDefinition?

    /**
     * Every row whose name matches [name] case-insensitively. Returns a list (bounded at 2)
     * rather than a single row so the resolver can report an ambiguous name instead of
     * silently picking whichever row SQLite happened to return first.
     */
    @Query("SELECT * FROM agent_definitions WHERE name = :name COLLATE NOCASE LIMIT 2")
    suspend fun byName(name: String): List<AgentDefinition>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(definition: AgentDefinition)

    @Query("DELETE FROM agent_definitions WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM agent_definitions")
    suspend fun deleteAll(): Int

    @Query("SELECT COUNT(*) FROM agent_definitions")
    suspend fun count(): Int
}
