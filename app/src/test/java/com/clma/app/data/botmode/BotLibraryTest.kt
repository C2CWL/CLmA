package com.clma.app.data.botmode

import com.clma.app.data.datastore.DisplaySetting
import com.clma.app.data.datastore.Settings
import com.clma.app.data.model.Assistant
import com.clma.app.data.model.AssistantLibrary
import com.clma.app.data.model.isBot
import com.clma.app.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class BotLibraryTest {

    private val classic = Assistant(id = Uuid.parse("11111111-1111-1111-1111-111111111111"), name = "Classic")
    private val botA = Assistant(id = Uuid.parse("22222222-2222-2222-2222-222222222222"), name = "A", library = AssistantLibrary.BOT)
    private val botB = Assistant(id = Uuid.parse("33333333-3333-3333-3333-333333333333"), name = "B", library = AssistantLibrary.BOT)

    @Test
    fun `old assistants decode as classic and classic stays byte-identical`() {
        val oldJson = """{"id":"${classic.id}","name":"Classic"}"""
        val decoded = JsonInstant.decodeFromString(Assistant.serializer(), oldJson)
        assertEquals(AssistantLibrary.CLASSIC, decoded.library)
        assertFalse(JsonInstant.encodeToString(Assistant.serializer(), classic).contains("\"library\""))
        val botJson = JsonInstant.encodeToString(Assistant.serializer(), botA)
        assertTrue(botJson.contains("\"library\":\"bot\""))
        assertTrue(JsonInstant.decodeFromString(Assistant.serializer(), botJson).isBot)
    }

    @Test
    fun `libraries are separate`() {
        val s = Settings(assistants = listOf(classic, botA, botB))
        assertEquals(listOf(classic), s.classicAssistants())
        assertEquals(listOf(botA, botB), s.botAssistants())
        assertTrue(s.botsForContactList().none { it.id == classic.id })
    }

    @Test
    fun `first bot mode run creates a main bot`() {
        val s = Settings(assistants = listOf(classic)).ensureBotLibrary { Assistant(name = "Main") }
        val main = s.mainBot()!!
        assertEquals("Main", main.name)
        assertTrue(main.isBot)
        assertEquals(listOf(classic), s.classicAssistants())
        // Idempotent once set up.
        assertSame(s, s.ensureBotLibrary { error("must not create twice") })
    }

    @Test
    fun `dangling main pointer is repaired without creating a bot`() {
        val s = Settings(
            assistants = listOf(classic, botA),
            displaySetting = DisplaySetting(botMainAssistantId = Uuid.random()),
        ).ensureBotLibrary { error("must reuse the existing bot") }
        assertEquals(botA.id, s.mainBot()?.id)
    }

    @Test
    fun `main bot is replaceable, listed first, not deletable`() {
        var s = Settings(
            assistants = listOf(classic, botA, botB),
            displaySetting = DisplaySetting(botMainAssistantId = botA.id, botHiddenAssistantIds = listOf(botB.id)),
        )
        assertEquals(listOf(botA), s.botsForContactList())
        assertFalse(s.canDeleteAssistant(botA.id))
        assertTrue(s.canDeleteAssistant(botB.id))

        s = s.withMainBot(botB.id)
        assertEquals(botB.id, s.mainBot()?.id)
        // The new main is un-hidden and leads; the old main stays as a normal bot.
        assertEquals(listOf(botB, botA), s.botsForContactList())
        assertTrue(s.canDeleteAssistant(botA.id))
        assertFalse(s.canDeleteAssistant(botB.id))
        // A classic assistant can never become the main bot.
        assertSame(s, s.withMainBot(classic.id))
    }

    @Test
    fun `current assistant follows the active library`() {
        val s = Settings(
            assistants = listOf(classic, botA),
            assistantId = botA.id,
            displaySetting = DisplaySetting(botMainAssistantId = botA.id),
        )
        assertSame(s, s.withAssistantInLibrary(botMode = true))
        assertEquals(classic.id, s.withAssistantInLibrary(botMode = false).assistantId)
        assertEquals(botA.id, s.copy(assistantId = classic.id).withAssistantInLibrary(botMode = true).assistantId)
        assertNull(Settings(assistants = listOf(classic)).mainBot())
    }
}
