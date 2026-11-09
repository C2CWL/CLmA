package com.clma.app.data.botmode

import com.clma.app.data.datastore.DisplaySetting
import com.clma.app.data.datastore.Settings
import com.clma.app.data.model.Assistant
import com.clma.app.data.model.AssistantLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class BotLibraryConversationsTest {

    private val classic = Assistant(id = Uuid.parse("11111111-1111-1111-1111-111111111111"), name = "Classic")
    private val botA = Assistant(id = Uuid.parse("22222222-2222-2222-2222-222222222222"), name = "A", library = AssistantLibrary.BOT)
    private val botB = Assistant(id = Uuid.parse("33333333-3333-3333-3333-333333333333"), name = "B", library = AssistantLibrary.BOT)
    private val orphan = Uuid.parse("44444444-4444-4444-4444-444444444444")

    private fun settings(botMode: Boolean) = Settings(
        assistantId = if (botMode) botA.id else classic.id,
        assistants = listOf(classic, botA, botB),
        displaySetting = DisplaySetting(botModeEnabled = botMode, botMainAssistantId = botA.id),
    )

    private data class Row(val name: String, val assistantId: Uuid)

    private val rows = listOf(
        Row("classic", classic.id),
        Row("botA", botA.id),
        Row("botB", botB.id),
        Row("orphan", orphan),
    )

    @Test
    fun `lists are filtered by the active entry`() {
        val classicMode = settings(botMode = false)
        assertEquals(listOf("classic", "orphan"), rows.inLibrary(classicMode, classicMode.activeLibraryIsBot()) { it.assistantId }.map { it.name })
        val botMode = settings(botMode = true)
        assertEquals(listOf("botA", "botB"), rows.inLibrary(botMode, botMode.activeLibraryIsBot()) { it.assistantId }.map { it.name })
    }

    @Test
    fun `orphaned conversations count as classic`() {
        val s = settings(botMode = true)
        assertFalse(s.isBotAssistantId(orphan))
        assertFalse(s.isInActiveLibrary(orphan))
        assertTrue(settings(botMode = false).isInActiveLibrary(orphan))
    }

    @Test
    fun `sql scope mirrors the list filter`() {
        val classicScope = settings(botMode = false).activeLibraryScope()
        val botScope = settings(botMode = true).activeLibraryScope()
        rows.forEach { row ->
            val id = row.assistantId.toString()
            val isBot = row.assistantId == botA.id || row.assistantId == botB.id
            assertEquals(row.name, !isBot, classicScope.allows(id))
            assertEquals(row.name, isBot, botScope.allows(id))
        }
        assertFalse(classicScope.isEmpty)
        assertTrue(Settings(assistants = listOf(classic)).libraryScope(bot = true).isEmpty)
        assertTrue(AssistantScope.ALL.allows(orphan.toString()))
    }

    @Test
    fun `model tools search the caller's own library`() {
        val s = settings(botMode = false)
        assertTrue(s.libraryScopeOf(botB.id).allows(botA.id.toString()))
        assertFalse(s.libraryScopeOf(botB.id).allows(classic.id.toString()))
        assertTrue(s.libraryScopeOf(classic.id).allows(orphan.toString()))
        assertFalse(s.libraryScopeOf(classic.id).allows(botA.id.toString()))
    }

    @Test
    fun `assistant pickers only offer the active library`() {
        assertEquals(listOf(classic), settings(botMode = false).activeLibraryAssistants())
        assertEquals(setOf(botA.id, botB.id), settings(botMode = true).activeLibraryAssistants().map { it.id }.toSet())
    }
}
