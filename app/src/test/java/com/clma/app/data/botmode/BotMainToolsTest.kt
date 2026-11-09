package com.clma.app.data.botmode

import com.clma.ai.core.Tool
import com.clma.ai.ui.UIMessagePart
import com.clma.app.connectors.ConnectorWriteApproval
import com.clma.app.data.ai.tools.ApprovalMode
import com.clma.app.data.ai.tools.HeadlessToolApprovalPolicy
import com.clma.app.data.ai.tools.ToolApprovalPolicy
import com.clma.app.data.datastore.DisplaySetting
import com.clma.app.data.datastore.Settings
import com.clma.app.data.model.Assistant
import com.clma.app.data.model.AssistantLibrary
import com.clma.app.data.model.AssistantMemory
import com.clma.app.data.model.isBot
import com.clma.app.utils.JsonInstant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class BotMainToolsTest {

    private val classic = Assistant(
        id = Uuid.parse("11111111-1111-1111-1111-111111111111"),
        name = "Classic",
        systemPrompt = "classic prompt",
    )
    private val main = Assistant(
        id = Uuid.parse("22222222-2222-2222-2222-222222222222"),
        name = "Main",
        library = AssistantLibrary.BOT,
    )
    private val other = Assistant(
        id = Uuid.parse("33333333-3333-3333-3333-333333333333"),
        name = "Other",
        systemPrompt = "old",
        library = AssistantLibrary.BOT,
    )

    /** In-memory store whose settings go through the real JSON encoding on every write. */
    private class FakeStore(initial: Settings) : BotMainToolStore {
        var current: Settings = initial
        val memoriesByBot = mutableMapOf<Uuid, MutableList<AssistantMemory>>()
        private var nextId = 1
        var writes = 0

        override suspend fun settings(): Settings = current
        override suspend fun updateSettings(transform: (Settings) -> Settings): Settings {
            writes++
            val next = transform(current)
            // Persist + reload, like the DataStore does.
            current = JsonInstant.decodeFromString(Settings.serializer(), JsonInstant.encodeToString(Settings.serializer(), next))
            return current
        }
        override suspend fun memories(botId: Uuid) = memoriesByBot[botId].orEmpty().toList()
        override suspend fun addMemory(botId: Uuid, content: String): AssistantMemory {
            writes++
            return AssistantMemory(nextId++, content).also { memoriesByBot.getOrPut(botId) { mutableListOf() } += it }
        }
        override suspend fun deleteMemory(memoryId: Int) {
            writes++
            memoriesByBot.values.forEach { list -> list.removeAll { it.id == memoryId } }
        }
    }

    private fun settings(mainId: Uuid? = main.id) = Settings(
        assistants = listOf(classic, main, other),
        displaySetting = DisplaySetting(botMainAssistantId = mainId),
    )

    private fun tools(caller: Uuid, store: BotMainToolStore, granted: Boolean): Map<String, Tool> =
        BotMainTools.build(caller, store, requireGrant = { _, _ -> granted }).associateBy { it.name }

    private fun Tool.run(args: JsonElement): JsonObject = runBlocking {
        val text = (execute(args).single() as UIMessagePart.Text).text
        JsonInstant.parseToJsonElement(text).jsonObject
    }

    private fun JsonObject.error(): String? = this["error"]?.jsonPrimitive?.content

    private val empty = buildJsonObject { }

    @Test
    fun `only the current main bot is authorised, and the permission moves with the pointer`() {
        val store = FakeStore(settings())
        assertTrue(BotMainTools.isMainAssistant(store.current, main))
        assertFalse(BotMainTools.isMainAssistant(store.current, other))
        assertFalse(BotMainTools.isMainAssistant(store.current, classic))

        assertEquals(null, tools(main.id, store, granted = true).getValue(BotMainTools.LIST).run(empty).error())
        assertEquals("not_main_assistant", tools(other.id, store, granted = true).getValue(BotMainTools.LIST).run(empty).error())

        // Replace the main assistant: the old main's tools stop working, even ones built earlier.
        val oldMainTools = tools(main.id, store, granted = true)
        store.current = store.current.withMainBot(other.id)
        assertEquals("not_main_assistant", oldMainTools.getValue(BotMainTools.LIST).run(empty).error())
        val create = buildJsonObject { put("name", "X") }
        assertEquals("not_main_assistant", oldMainTools.getValue(BotMainTools.CREATE).run(create).error())
        assertEquals(null, tools(other.id, store, granted = true).getValue(BotMainTools.LIST).run(empty).error())
        assertEquals(3, store.current.assistants.size)
    }

    @Test
    fun `a classic assistant pointed at as main never gets the tools`() {
        val store = FakeStore(settings(mainId = classic.id))
        assertFalse(BotMainTools.isMainAssistant(store.current, classic))
        assertEquals("not_main_assistant", tools(classic.id, store, granted = true).getValue(BotMainTools.LIST).run(empty).error())
    }

    @Test
    fun `list shows only bots`() {
        val store = FakeStore(settings())
        val out = tools(main.id, store, granted = true).getValue(BotMainTools.LIST).run(empty)
        val ids = out.getValue("bots").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
        assertEquals(listOf(main.id.toString(), other.id.toString()), ids)
        assertFalse(out.toString().contains("classic prompt"))
    }

    @Test
    fun `writes without the user's approval change nothing`() {
        val store = FakeStore(settings())
        val t = tools(main.id, store, granted = false)
        val calls = listOf(
            BotMainTools.CREATE to buildJsonObject { put("name", "New") ; put("system_prompt", "p") },
            BotMainTools.UPDATE_PROMPT to buildJsonObject { put("bot_id", other.id.toString()); put("system_prompt", "new") },
            BotMainTools.MEMORY_WRITE to buildJsonObject { put("bot_id", other.id.toString()); put("content", "m") },
            BotMainTools.MEMORY_DELETE to buildJsonObject { put("bot_id", other.id.toString()); put("memory_id", 1) },
        )
        calls.forEach { (name, args) ->
            assertEquals(name, "approval_required", t.getValue(name).run(args).error())
        }
        assertEquals(0, store.writes)
        assertEquals("old", store.current.assistants.first { it.id == other.id }.systemPrompt)
    }

    @Test
    fun `approved writes persist and only touch bots`() {
        val store = FakeStore(settings())
        val t = tools(main.id, store, granted = true)

        val created = t.getValue(BotMainTools.CREATE).run(buildJsonObject {
            put("name", "Writer")
            put("system_prompt", "You write.")
        })
        val newId = Uuid.parse(created.getValue("id").jsonPrimitive.content)
        val stored = store.current.assistants.first { it.id == newId }
        assertTrue(stored.isBot)
        assertEquals("You write.", stored.systemPrompt)
        // Survives a full encode / decode of the settings (the persisted form).
        val reloaded = JsonInstant.decodeFromString(Settings.serializer(), JsonInstant.encodeToString(Settings.serializer(), store.current))
        assertTrue(reloaded.botAssistants().any { it.id == newId && it.name == "Writer" })
        assertTrue(reloaded.classicAssistants().none { it.id == newId })

        assertEquals(null, t.getValue(BotMainTools.UPDATE_PROMPT).run(buildJsonObject {
            put("bot_id", other.id.toString()); put("system_prompt", "new prompt")
        }).error())
        assertEquals("new prompt", store.current.assistants.first { it.id == other.id }.systemPrompt)

        val mem = t.getValue(BotMainTools.MEMORY_WRITE).run(buildJsonObject {
            put("bot_id", other.id.toString()); put("content", "likes tea")
        })
        val memId = mem.getValue("memory_id").jsonPrimitive.intOrNull!!
        assertEquals(listOf("likes tea"), store.memoriesByBot.getValue(other.id).map { it.content })

        // A memory id of another bot is refused.
        assertEquals("memory_not_found", t.getValue(BotMainTools.MEMORY_DELETE).run(buildJsonObject {
            put("bot_id", main.id.toString()); put("memory_id", memId)
        }).error())
        assertEquals(null, t.getValue(BotMainTools.MEMORY_DELETE).run(buildJsonObject {
            put("bot_id", other.id.toString()); put("memory_id", memId)
        }).error())
        assertTrue(store.memoriesByBot.getValue(other.id).isEmpty())
    }

    @Test
    fun `classic assistants cannot be changed`() {
        val store = FakeStore(settings())
        val t = tools(main.id, store, granted = true)
        assertEquals("bot_not_found", t.getValue(BotMainTools.UPDATE_PROMPT).run(buildJsonObject {
            put("bot_id", classic.id.toString()); put("system_prompt", "hacked")
        }).error())
        assertEquals("bot_not_found", t.getValue(BotMainTools.MEMORY_WRITE).run(buildJsonObject {
            put("bot_id", classic.id.toString()); put("content", "x")
        }).error())
        assertEquals("bot_not_found", t.getValue(BotMainTools.MEMORY_DELETE).run(buildJsonObject {
            put("bot_id", classic.id.toString()); put("memory_id", 1)
        }).error())
        assertEquals("classic prompt", store.current.assistants.first { it.id == classic.id }.systemPrompt)
        assertTrue(store.memoriesByBot[classic.id].isNullOrEmpty())
    }

    @Test
    fun `bad arguments are refused without writing`() {
        val store = FakeStore(settings())
        val t = tools(main.id, store, granted = true)
        assertEquals("invalid_tool_args", t.getValue(BotMainTools.CREATE).run(buildJsonObject { put("name", " ") }).error())
        assertEquals("invalid_tool_args", t.getValue(BotMainTools.CREATE).run(buildJsonObject {
            put("name", "A"); put("model_id", Uuid.random().toString())
        }).error())
        assertEquals("invalid_tool_args", t.getValue(BotMainTools.UPDATE_PROMPT).run(buildJsonObject {
            put("bot_id", "not-a-uuid"); put("system_prompt", "x")
        }).error())
        assertEquals(0, store.writes)
    }

    @Test
    fun `writes always need a per-call approval that auto mode and chat grants cannot give`() {
        val tools = BotMainTools.build(main.id, FakeStore(settings())).associateBy { it.name }
        BotMainTools.WRITE_TOOL_NAMES.forEach { name ->
            assertTrue(name, tools.getValue(name).needsApproval(empty))
            assertTrue(name, ConnectorWriteApproval.requiresApproval(name))
            ApprovalMode.entries.forEach { mode ->
                assertFalse(
                    "$name auto-approved in $mode",
                    ToolApprovalPolicy.shouldAutoApprove(name, emptySet(), mode, headless = false, allowedForChat = true),
                )
            }
            assertNotNull("$name must be refused headless", HeadlessToolApprovalPolicy.refusalDetail(name))
        }
        assertFalse(tools.getValue(BotMainTools.LIST).needsApproval(empty))
        assertFalse(ConnectorWriteApproval.requiresApproval(BotMainTools.LIST))
    }

    @Test
    fun `a denied or unapproved call never runs the write, an approved one runs once`() = runBlocking {
        val store = FakeStore(settings())
        // Real grant check (the default), exactly as wired in ChatService.
        val create = BotMainTools.build(main.id, store).first { it.name == BotMainTools.CREATE }
        val args = buildJsonObject { put("name", "Once") }
        val conv = Uuid.random().toString()
        val call = "call-1"

        // Denied: GenerationLoop never reaches execute; a stale Approved state without the
        // single-use consent is refused too.
        val refused = ConnectorWriteApproval.execute(create, args, conv, call, approved = true, headless = false)
        assertTrue((refused.single() as UIMessagePart.Text).text.contains("approval_required"))
        assertEquals(0, store.writes)

        // Calling the tool body directly (no grant in the context) refuses as well.
        val direct = (create.execute(args).single() as UIMessagePart.Text).text
        assertTrue(direct.contains("approval_required"))
        assertEquals(0, store.writes)

        // The user's 「仅此一次」 tap.
        ConnectorWriteApproval.approveOnce(conv, call, create.name, args)
        val ok = (ConnectorWriteApproval.execute(create, args, conv, call, approved = true, headless = false).single() as UIMessagePart.Text).text
        assertTrue(ok, ok.contains("\"created\":true"))
        assertEquals(1, store.current.assistants.count { it.name == "Once" })

        // Replay of the same call is refused.
        ConnectorWriteApproval.execute(create, args, conv, call, approved = true, headless = false)
        assertEquals(1, store.current.assistants.count { it.name == "Once" })
    }

    @Test
    fun `new bots from the plus button and the tool share one shape`() {
        val bot = BotMainTools.newBot("  N  ", "  p  ")
        assertEquals("N", bot.name)
        assertEquals("p", bot.systemPrompt)
        assertTrue(bot.isBot)
    }
}
