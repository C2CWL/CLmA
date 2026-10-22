package me.rerere.rikkahub.subagent

import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.agentdef.AgentDefinition
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.model.Assistant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * P2-04, extended by P2-06b — a sub-agent's own tool surface.
 *
 * Two contracts are load-bearing here:
 *
 *  - [SubAgentSurface.resolveChildAssistant] returns `null` (="inherit the parent verbatim") for
 *    every expert that does not define a surface, so the pre-P2-04 behaviour is preserved for
 *    each existing "model + system prompt" specialist; and
 *  - [SubAgentSurface.apply] still applies the headless FLOOR per tool name to whatever surface the
 *    run ends up with, so "child ⊆ parent" is replaced by "child has its own surface, minus the
 *    tools nobody may run unattended" rather than by "child has anything it likes".
 *
 * P2-06b moved the overlay itself into [me.rerere.rikkahub.data.agentdef.toAssistant] and widened
 * "own surface" to include `skills` and the D9 `slug`, so the derived assistant is now assembled in
 * exactly one place and a namespace-only expert still gets the floor applied.
 */
class SubAgentSurfaceTest {

    private val parentId = Uuid.parse("00000000-0000-0000-0000-0000000000aa")
    private val workspaceId = Uuid.parse("00000000-0000-0000-0000-0000000000bb")
    private val mcpA = Uuid.parse("00000000-0000-0000-0000-0000000000c1")
    private val mcpB = Uuid.parse("00000000-0000-0000-0000-0000000000c2")

    private fun parent() = Assistant(
        id = parentId,
        name = "parent",
        localTools = listOf(LocalToolOption.TimeInfo, LocalToolOption.Workflows),
        disabledLocalTools = setOf("read_sensor"),
        mcpServers = setOf(mcpA),
        enableWebSearch = true,
        workspaceId = workspaceId,
        enabledSkills = setOf("skill-x"),
        enableSubAgentToolSurface = true,
    )

    /** A stored expert. Only [AgentDefinition.id] + `name` are required by the entity. */
    private fun definition(
        localTools: List<LocalToolOption>? = null,
        disabledLocalTools: Set<String>? = null,
        mcpServers: Set<String>? = null,
        skills: Set<String>? = null,
        slug: String? = null,
        name: String = "specialist",
    ) = AgentDefinition(
        id = "def-1",
        name = name,
        localTools = localTools,
        disabledLocalTools = disabledLocalTools,
        mcpServers = mcpServers,
        skills = skills,
        slug = slug,
    )

    private fun tool(name: String): Tool = Tool(name = name, description = "") { emptyList() }

    private fun namesOf(tools: List<Tool>): List<String> = tools.map { it.name }

    @After
    fun tearDown() {
        // The registry is process-global; leaving records behind would leak between tests.
        SubAgentSurface.clearAll()
    }

    // ---- resolveChildAssistant --------------------------------------------------------

    @Test
    fun `no definition means inherit`() {
        assertNull(SubAgentSurface.resolveChildAssistant(parent(), null))
    }

    @Test
    fun `no parent assistant means inherit`() {
        assertNull(
            SubAgentSurface.resolveChildAssistant(
                null,
                definition(localTools = listOf(LocalToolOption.TimeInfo)),
            ),
        )
    }

    @Test
    fun `a definition that only picks a model and prompt has no surface of its own`() {
        val plain = definition(name = "plain").copy(
            description = "d",
            systemPrompt = "p",
            modelId = Uuid.random().toString(),
        )
        assertFalse(SubAgentSurface.hasOwnSurface(plain))
        assertNull(SubAgentSurface.resolveChildAssistant(parent(), plain))
    }

    @Test
    fun `an own local tool list replaces the parent's and leaves every other field inherited`() {
        val parent = parent()
        val child = SubAgentSurface.resolveChildAssistant(
            parent,
            definition(localTools = listOf(LocalToolOption.SubAgents)),
        )
        assertNotNull(child)
        child!!
        // The one thing the expert had an opinion about.
        assertEquals(listOf(LocalToolOption.SubAgents), child.localTools)
        // Everything the expert did not mention still comes from the parent.
        assertEquals(parent.disabledLocalTools, child.disabledLocalTools)
        assertEquals(parent.mcpServers, child.mcpServers)
        // The child is the SAME assistant for everything except the tool face...
        assertEquals(parent.id, child.id)
        assertEquals(parent.enableWebSearch, child.enableWebSearch)
        assertEquals(parent.workspaceId, child.workspaceId)
        assertEquals(parent.enabledSkills, child.enabledSkills)
        assertEquals(parent.enableSubAgentToolSurface, child.enableSubAgentToolSurface)
        // ...and its name is the expert's, which is what makes the derived assistant readable in
        // a log even though nothing looks it up by name.
        assertEquals("specialist", child.name)
    }

    @Test
    fun `an explicit empty local tool list is an opinion, not an inheritance`() {
        // emptyList() != null: the expert asked for a (useless but deliberate) empty surface.
        val child = SubAgentSurface.resolveChildAssistant(parent(), definition(localTools = emptyList()))
        assertNotNull(child)
        assertTrue(child!!.localTools.isEmpty())
    }

    @Test
    fun `a per-tool opt-out alone is enough to give the child its own surface`() {
        val parent = parent()
        val child = SubAgentSurface.resolveChildAssistant(
            parent,
            definition(disabledLocalTools = setOf("show_toast")),
        )
        assertNotNull(child)
        assertEquals(setOf("show_toast"), child!!.disabledLocalTools)
        assertEquals(parent.localTools, child.localTools)
        assertEquals(parent.mcpServers, child.mcpServers)
    }

    @Test
    fun `an mcp server choice alone is enough to give the child its own surface`() {
        val parent = parent()
        val child = SubAgentSurface.resolveChildAssistant(
            parent,
            definition(mcpServers = setOf(mcpA.toString(), mcpB.toString())),
        )
        assertNotNull(child)
        assertEquals(setOf(mcpA, mcpB), child!!.mcpServers)
        assertEquals(parent.localTools, child.localTools)
        assertEquals(parent.disabledLocalTools, child.disabledLocalTools)
    }

    @Test
    fun `all three tool fields together are taken from the expert`() {
        val child = SubAgentSurface.resolveChildAssistant(
            parent(),
            definition(
                localTools = listOf(LocalToolOption.Workflows),
                disabledLocalTools = setOf("read_sensor", "show_toast"),
                mcpServers = setOf(mcpB.toString()),
            ),
        )
        assertNotNull(child)
        assertEquals(listOf(LocalToolOption.Workflows), child!!.localTools)
        assertEquals(setOf("read_sensor", "show_toast"), child.disabledLocalTools)
        assertEquals(setOf(mcpB), child.mcpServers)
    }

    @Test
    fun `a skill set alone gives the child its own surface and replaces the parent's skills`() {
        val child = SubAgentSurface.resolveChildAssistant(
            parent(),
            definition(skills = setOf("skill-y")),
        )
        assertNotNull(child)
        assertEquals(setOf("skill-y"), child!!.enabledSkills)
        // The skills-only expert did not touch the tool face, so that still comes from the parent.
        assertEquals(parent().localTools, child.localTools)
    }

    @Test
    fun `a slug alone gives the child its own surface and a namespace-scoped cold memory dir`() {
        val child = SubAgentSurface.resolveChildAssistant(parent(), definition(slug = "deep-research"))
        assertNotNull(child)
        assertEquals("agents/deep-research/memory", child!!.coldMemoryDir)
        // The namespace does NOT change the workspace - it is a subdirectory of the parent's.
        assertEquals(parent().workspaceId, child.workspaceId)
    }

    @Test
    fun `an unusable slug does not invent a surface`() {
        // A slug that normalises to nothing (e.g. an all-CJK name) must not silently claim a
        // namespace; the definition then has no surface and inherits verbatim.
        val noNamespace = definition().copy(slug = "  ")
        assertFalse(SubAgentSurface.hasOwnSurface(noNamespace))
        assertNull(SubAgentSurface.resolveChildAssistant(parent(), noNamespace))
    }

    @Test
    fun `the child can carry a tool group the parent does not have`() {
        // The whole point of P2-04: a specialist is not limited to the parent's surface.
        val parent = parent().copy(localTools = listOf(LocalToolOption.TimeInfo))
        val child = SubAgentSurface.resolveChildAssistant(
            parent,
            definition(localTools = listOf(LocalToolOption.SubAgents)),
        )
        assertEquals(listOf(LocalToolOption.SubAgents), child!!.localTools)
        assertFalse(child.localTools.containsAll(parent.localTools))
    }

    @Test
    fun `a malformed mcp server id is dropped rather than failing the surface`() {
        val child = SubAgentSurface.resolveChildAssistant(
            parent(),
            definition(mcpServers = setOf("not-a-uuid", mcpB.toString())),
        )
        assertNotNull(child)
        assertEquals(setOf(mcpB), child!!.mcpServers)
    }

    @Test
    fun `hasOwnSurface is true for each field and false for none`() {
        assertFalse(SubAgentSurface.hasOwnSurface(null))
        assertFalse(SubAgentSurface.hasOwnSurface(definition()))
        assertTrue(SubAgentSurface.hasOwnSurface(definition(localTools = emptyList())))
        assertTrue(SubAgentSurface.hasOwnSurface(definition(disabledLocalTools = emptySet())))
        assertTrue(SubAgentSurface.hasOwnSurface(definition(mcpServers = emptySet())))
        assertTrue(SubAgentSurface.hasOwnSurface(definition(skills = emptySet())))
        assertTrue(SubAgentSurface.hasOwnSurface(definition(slug = "research")))
    }

    // ---- freeze registry --------------------------------------------------------------

    @Test
    fun `apply returns the same instance when the conversation was never frozen`() {
        val tools = listOf(tool("search_web"), tool("subagent_dispatch"), tool("take_photo"))
        val frozen = Uuid.random()
        SubAgentSurface.freeze(frozen)
        assertSame(tools, SubAgentSurface.apply(Uuid.random(), tools))
        assertNull(SubAgentSurface.frozenFor(Uuid.random()))
        assertNotNull(SubAgentSurface.frozenFor(frozen))
        assertNull(SubAgentSurface.assistantFor(frozen))
    }

    @Test
    fun `a frozen conversation loses the denied tools and keeps the rest`() {
        val conversation = Uuid.random()
        SubAgentSurface.freeze(conversation)
        val tools = listOf(
            tool("search_web"),
            tool("subagent_dispatch"),
            tool("ask_user"),
            tool("eval_javascript"),
            tool("take_photo"),
            tool("mcp__github__get_me"),
        )
        val filtered = SubAgentSurface.apply(conversation, tools)
        assertEquals(listOf("search_web", "mcp__github__get_me"), namesOf(filtered))
        // The surviving tools are the very same instances, not copies.
        assertSame(tools[0], filtered[0])
        assertSame(tools[5], filtered[1])
        // The caller's list is untouched.
        assertEquals(6, tools.size)
    }

    @Test
    fun `a frozen conversation with nothing to remove still gets the original instance`() {
        val conversation = Uuid.random()
        SubAgentSurface.freeze(conversation, requested = listOf("search_web"))
        val tools = listOf(tool("search_web"))
        assertSame(tools, SubAgentSurface.apply(conversation, tools))
    }

    @Test
    fun `a requested allow list narrows the frozen surface`() {
        val conversation = Uuid.random()
        SubAgentSurface.freeze(conversation, requested = setOf("search_web", "web_fetch"))
        val tools = listOf(tool("search_web"), tool("web_fetch"), tool("workspace_shell"), tool("memory_tool"))
        assertEquals(listOf("search_web", "web_fetch"), namesOf(SubAgentSurface.apply(conversation, tools)))
    }

    @Test
    fun `requested names that do not exist in the surface are simply dropped`() {
        val conversation = Uuid.random()
        SubAgentSurface.freeze(conversation, requested = setOf("search_web", "no_such_tool"))
        val tools = listOf(tool("search_web"), tool("memory_tool"))
        assertEquals(listOf("search_web"), namesOf(SubAgentSurface.apply(conversation, tools)))
        assertEquals(setOf("search_web", "no_such_tool"), SubAgentSurface.frozenFor(conversation)?.requested)
    }

    @Test
    fun `an empty or blank requested list means no narrowing rather than no tools`() {
        val empty = Uuid.random()
        assertNull(SubAgentSurface.freeze(empty, requested = emptyList()).requested)
        val blank = Uuid.random()
        assertNull(SubAgentSurface.freeze(blank, requested = listOf("", "   ")).requested)
        val tools = listOf(tool("search_web"), tool("memory_tool"), tool("take_photo"))
        assertEquals(listOf("search_web", "memory_tool"), namesOf(SubAgentSurface.apply(empty, tools)))
        assertEquals(listOf("search_web", "memory_tool"), namesOf(SubAgentSurface.apply(blank, tools)))
    }

    @Test
    fun `a denial beats a requested allow list`() {
        val conversation = Uuid.random()
        // Even if a dispatcher asks for it explicitly, a denied tool must not be handed over.
        SubAgentSurface.freeze(conversation, requested = setOf("search_web", "eval_javascript"))
        val tools = listOf(tool("search_web"), tool("eval_javascript"))
        assertEquals(listOf("search_web"), namesOf(SubAgentSurface.apply(conversation, tools)))
    }

    @Test
    fun `the floor strips a denied tool the child's own surface asked for`() {
        // P2-04's core safety claim: an expert that enables an approval-required group gets a
        // surface, but the floor still removes every tool nobody may run unattended.
        val conversation = Uuid.random()
        val child = SubAgentSurface.resolveChildAssistant(
            parent(),
            definition(localTools = listOf(LocalToolOption.TimeInfo, LocalToolOption.Battery)),
        )
        SubAgentSurface.freeze(conversation, assistant = child)
        val tools = listOf(tool("get_battery_status"), tool("eval_javascript"), tool("record_audio"), tool("ask_user"))
        assertEquals(listOf("get_battery_status"), namesOf(SubAgentSurface.apply(conversation, tools)))
    }

    @Test
    fun `the floor also strips the expert-library writers from a child surface`() {
        // P2-06b: subagent_create/update/delete start with `subagent_`, so the prefix rule keeps
        // them off every derived child surface even though they are registered on the parent.
        val conversation = Uuid.random()
        SubAgentSurface.freeze(conversation, assistant = parent())
        val tools = listOf(
            tool("subagent_create"),
            tool("subagent_update"),
            tool("subagent_delete"),
            tool("subagent_list"),
            tool("search_web"),
        )
        assertEquals(listOf("search_web"), namesOf(SubAgentSurface.apply(conversation, tools)))
    }

    @Test
    fun `release restores the identity behaviour`() {
        val conversation = Uuid.random()
        val tools = listOf(tool("search_web"), tool("subagent_dispatch"))
        SubAgentSurface.freeze(conversation, assistant = parent())
        assertEquals(listOf("search_web"), namesOf(SubAgentSurface.apply(conversation, tools)))
        SubAgentSurface.release(conversation)
        assertNull(SubAgentSurface.frozenFor(conversation))
        assertNull(SubAgentSurface.assistantFor(conversation))
        assertSame(tools, SubAgentSurface.apply(conversation, tools))
    }

    @Test
    fun `assistantFor hands back exactly the frozen surface assistant`() {
        val conversation = Uuid.random()
        val child = SubAgentSurface.resolveChildAssistant(
            parent(),
            definition(mcpServers = setOf(mcpB.toString())),
        )
        assertNotNull(child)
        SubAgentSurface.freeze(conversation, assistant = child)
        assertSame(child, SubAgentSurface.assistantFor(conversation))
        assertSame(child, SubAgentSurface.frozenFor(conversation)?.assistant)
    }

    @Test
    fun `freezes are per conversation and free of cross-talk`() {
        val narrowed = Uuid.random()
        val widened = Uuid.random()
        SubAgentSurface.freeze(narrowed, requested = listOf("search_web"))
        SubAgentSurface.freeze(widened, assistant = parent())
        val tools = listOf(tool("search_web"), tool("memory_tool"), tool("ask_user"))

        assertEquals(listOf("search_web"), namesOf(SubAgentSurface.apply(narrowed, tools)))
        assertEquals(listOf("search_web", "memory_tool"), namesOf(SubAgentSurface.apply(widened, tools)))
        assertSame(tools, SubAgentSurface.apply(Uuid.random(), tools))
        assertNotNull(SubAgentSurface.assistantFor(widened))
        assertNull(SubAgentSurface.assistantFor(narrowed))
    }

    @Test
    fun `clearAll unbinds every conversation`() {
        val conversation = Uuid.random()
        val tools = listOf(tool("search_web"), tool("ask_user"))
        SubAgentSurface.freeze(conversation, assistant = parent())
        assertEquals(1, SubAgentSurface.apply(conversation, tools).size)
        SubAgentSurface.clearAll()
        assertSame(tools, SubAgentSurface.apply(conversation, tools))
        assertNull(SubAgentSurface.assistantFor(conversation))
    }

    @Test
    fun `freezing the same conversation twice replaces the record`() {
        val conversation = Uuid.random()
        SubAgentSurface.freeze(conversation, assistant = parent(), requested = listOf("search_web"))
        assertNotNull(SubAgentSurface.assistantFor(conversation))
        SubAgentSurface.freeze(conversation)
        assertNull(SubAgentSurface.frozenFor(conversation)?.requested)
        assertNull(SubAgentSurface.assistantFor(conversation))
    }
}
