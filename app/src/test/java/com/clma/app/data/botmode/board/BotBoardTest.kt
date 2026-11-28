package com.clma.app.data.botmode.board

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BotBoardTest {
    private val board = BotBoard(
        botIds = setOf("chief", "lead", "worker", "peer"),
        reportsTo = mapOf("worker" to "lead", "peer" to "classic"),
        coordinatorIds = setOf("chief"),
    )

    @Test
    fun `a Bot claims a user task and takes it through review to done`() {
        val task = board.create(emptyList(), null, "  Draft\n release notes  ", " First draft ")
        assertEquals("Draft release notes", task.title)
        assertEquals("First draft", task.description)
        assertNull(task.createdByBotId)
        assertNull(task.ownerBotId)
        assertEquals(BoardStatus.TODO, task.status)

        val claimed = board.claim(task, "worker")
        assertEquals("worker", claimed.ownerBotId)
        assertEquals(BoardStatus.IN_PROGRESS, claimed.status)
        assertThrows(IllegalArgumentException::class.java) { board.claim(claimed, "peer") }

        val reviewed = board.update(listOf(claimed), claimed, "worker", BoardStatus.REVIEW, null, null)
        assertEquals(BoardStatus.REVIEW, reviewed.status)
        assertThrows(IllegalArgumentException::class.java) {
            board.update(listOf(reviewed), reviewed, "peer", BoardStatus.DONE, null, null)
        }
        val done = board.update(listOf(reviewed), reviewed, null, BoardStatus.DONE, null, null)
        assertEquals(BoardStatus.DONE, done.status)
        assertEquals("worker", done.ownerBotId)
    }

    @Test
    fun `reports-to and chief settings govern assignment and protect others' work`() {
        val task = board.create(emptyList(), "lead", "Ship", "")
        val assigned = board.assign(task, "lead", "worker")
        assertEquals("lead", assigned.createdByBotId)
        assertEquals("worker", assigned.ownerBotId)
        assertEquals(BoardStatus.TODO, assigned.status)
        assertThrows(IllegalArgumentException::class.java) { board.claim(assigned, "peer") }
        assertThrows(IllegalArgumentException::class.java) { board.assign(assigned, "lead", "peer") }
        assertThrows(IllegalArgumentException::class.java) { board.assign(task, "classic", "peer") }
        assertThrows(IllegalArgumentException::class.java) { board.assign(task, "chief", "classic") }

        val active = board.claim(assigned, "worker")
        val reassigned = board.assign(active, "chief", "peer")
        assertEquals("peer", reassigned.ownerBotId)
        assertEquals(BoardStatus.TODO, reassigned.status)
        assertNull(board.assign(reassigned, null, null).ownerBotId)
    }

    @Test
    fun `user and Bot comments retain their author and survive a JSON round trip`() {
        val task = board.create(emptyList(), "worker", "Draft", "")
        val withBot = board.comment(task, "peer", " Ready to review ")
        val withUser = board.comment(withBot, null, "Thanks")
        assertEquals(listOf("peer", null), withUser.comments.map { it.botId })
        assertEquals(listOf("Ready to review", "Thanks"), withUser.comments.map { it.text })
        assertEquals(withUser, Json.decodeFromString<BoardTask>(Json.encodeToString(withUser)))
        assertNotEquals(task.id, board.create(emptyList(), "worker", "Draft", "").id)
        assertThrows(IllegalArgumentException::class.java) { board.comment(withUser, "classic", "Invisible") }
        assertThrows(IllegalArgumentException::class.java) { board.comment(withUser, null, "  ") }
    }

    @Test
    fun `a missing Bot does not strand a claim and an open limit also applies to reopening`() {
        val task = board.create(emptyList(), null, "Draft", "")
        val orphan = task.copy(status = BoardStatus.IN_PROGRESS, ownerBotId = "removed")
        assertEquals("peer", board.claim(orphan, "peer").ownerBotId)

        val full = List(200) { task.copy(id = it.toString()) }
        assertThrows(IllegalArgumentException::class.java) { board.create(full, null, "Extra", "") }
        val done = task.copy(status = BoardStatus.DONE)
        assertThrows(IllegalArgumentException::class.java) {
            board.update(full + done, done, null, BoardStatus.TODO, null, null)
        }
    }
}
