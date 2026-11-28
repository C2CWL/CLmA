// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), packages/phone-protocol/src/board.ts, server/board/state.ts,
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0
// (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.data.botmode.board

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
internal enum class BoardStatus(val key: String) {
    @SerialName("todo") TODO("todo"),
    @SerialName("in_progress") IN_PROGRESS("in_progress"),
    @SerialName("review") REVIEW("review"),
    @SerialName("done") DONE("done"),
}

@Serializable
internal data class BoardComment(val botId: String?, val text: String, val createdAt: Long)

@Serializable
internal data class BoardTask(
    val id: String,
    val title: String,
    val description: String,
    val createdByBotId: String?,
    val ownerBotId: String? = null,
    val status: BoardStatus = BoardStatus.TODO,
    val createdAt: Long,
    val comments: List<BoardComment> = emptyList(),
)

internal class BotBoard(
    private val botIds: Set<String>,
    private val reportsTo: Map<String, String?> = emptyMap(),
    private val coordinatorIds: Set<String> = emptySet(),
) {
    fun requireMember(botId: String?) {
        require(botId == null || botId in botIds) { "You must be a current Bot team member." }
    }

    fun canAssign(actor: String?, target: String): Boolean =
        target in botIds && (actor == null ||
            (actor in botIds && (actor in coordinatorIds || reportsTo[target] == actor)))

    fun create(tasks: List<BoardTask>, actor: String?, title: String, description: String): BoardTask {
        requireMember(actor)
        requireSpace(tasks)
        return BoardTask(
            id = UUID.randomUUID().toString(),
            title = cleanTitle(title),
            description = cleanDescription(description),
            createdByBotId = actor,
            createdAt = System.currentTimeMillis(),
        )
    }

    fun claim(task: BoardTask, actor: String): BoardTask {
        requireMember(actor)
        require(task.status == BoardStatus.TODO ||
            (task.status == BoardStatus.IN_PROGRESS && task.ownerBotId !in botIds)) {
            "Only to-do tasks or in-progress tasks with a missing owner can be claimed."
        }
        require(task.ownerBotId == actor || task.ownerBotId !in botIds) { "This task belongs to another Bot." }
        return task.copy(ownerBotId = actor, status = BoardStatus.IN_PROGRESS)
    }

    fun assign(task: BoardTask, actor: String?, target: String?): BoardTask {
        requireMember(actor)
        require(task.status != BoardStatus.DONE) { "Reopen this task before assigning it." }
        require(target == null || canAssign(actor, target)) { "You cannot assign work to this Bot." }
        val current = task.ownerBotId
        require(actor == null || current == null || current !in botIds || current == actor || canAssign(actor, current)) {
            "You cannot reassign work owned by this Bot."
        }
        require(actor == null || target != null) { "Only the user can clear the owner." }
        return task.copy(
            ownerBotId = target,
            status = if (current == target) task.status else BoardStatus.TODO,
        )
    }

    fun update(
        tasks: List<BoardTask>,
        task: BoardTask,
        actor: String?,
        status: BoardStatus?,
        title: String?,
        description: String?,
    ): BoardTask {
        requireMember(actor)
        require(status != null || title != null || description != null) { "Specify a change." }
        require(actor == null || task.ownerBotId == actor ||
            (task.status == BoardStatus.TODO && task.createdByBotId == actor && task.ownerBotId !in botIds &&
                status == null)) { "Claim the task before updating it." }
        val nextStatus = status ?: task.status
        if (task.status == BoardStatus.DONE && nextStatus != BoardStatus.DONE) requireSpace(tasks)
        require(nextStatus != BoardStatus.IN_PROGRESS && nextStatus != BoardStatus.REVIEW ||
            task.ownerBotId in botIds) { "Assign the task to a current Bot first." }
        return task.copy(
            status = nextStatus,
            title = title?.let(::cleanTitle) ?: task.title,
            description = description?.let(::cleanDescription) ?: task.description,
        )
    }

    fun comment(task: BoardTask, actor: String?, text: String): BoardTask {
        requireMember(actor)
        val cleaned = text.trim()
        require(cleaned.isNotEmpty() && cleaned.length <= MAX_TEXT) { "Comment must be 1–$MAX_TEXT characters." }
        require(task.comments.size < 200) { "This task has reached its comment limit." }
        return task.copy(comments = task.comments + BoardComment(actor, cleaned, System.currentTimeMillis()))
    }

    private fun requireSpace(tasks: List<BoardTask>) {
        require(tasks.count { it.status != BoardStatus.DONE } < 200) { "The board has 200 open tasks." }
    }

    private fun cleanTitle(raw: String): String = raw.trim().split(Regex("\\s+")).joinToString(" ").also {
        require(it.isNotEmpty() && it.length <= MAX_TITLE) { "Title must be 1–$MAX_TITLE characters." }
    }

    private fun cleanDescription(raw: String): String = raw.trim().also {
        require(it.length <= MAX_TEXT) { "Description must be at most $MAX_TEXT characters." }
    }

    companion object {
        const val MAX_TITLE = 120
        const val MAX_TEXT = 2_000
    }
}
