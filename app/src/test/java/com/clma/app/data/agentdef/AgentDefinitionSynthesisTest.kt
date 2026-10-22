package me.rerere.rikkahub.data.agentdef

import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-06 — "definition → assistant" (D6).
 *
 * The load-bearing property is **inertness**: a definition that sets no optional field must
 * produce an assistant identical to the parent except for its name, because that is what keeps
 * the pre-P2-04 behaviour intact for every expert that only picks a model and a prompt. The
 * rest pins the null-vs-empty distinction, which is the same trap P2-04 had to get right: an
 * empty list is an *instruction* ("this expert gets no local tools"), not "inherit".
 */
class AgentDefinitionSynthesisTest {

    private val parentId = Uuid.parse("00000000-0000-0000-0000-0000000000aa")
    private val workspaceId = Uuid.parse("00000000-0000-0000-0000-0000000000bb")
    private val mcpA = Uuid.parse("00000000-0000-0000-0000-0000000000c1")
    private val mcpB = Uuid.parse("00000000-0000-0000-0000-0000000000c2")

    private fun parent() = Assistant(
        id = parentId,
        name = "parent",
        systemPrompt = "parent prompt",
        chatModelId = Uuid.parse("00000000-0000-0000-0000-0000000000dd"),
        localTools = listOf(LocalToolOption.TimeInfo, LocalToolOption.Workflows),
        disabledLocalTools = setOf("read_sensor"),
        mcpServers = setOf(mcpA),
        enabledSkills = setOf("skill-x"),
        workspaceId = workspaceId,
        coldMemoryEnabled = true,
        coldMemoryDir = "parent-memory",
    )

    private fun definition(
        name: String = "specialist",
        slug: String? = null,
        localTools: List<LocalToolOption>? = null,
        disabledLocalTools: Set<String>? = null,
        mcpServers: Set<String>? = null,
        skills: Set<String>? = null,
    ) = AgentDefinition(
        id = "def-1",
        name = name,
        systemPrompt = "expert prompt",
        modelId = "00000000-0000-0000-0000-0000000000ee",
        localTools = localTools,
        disabledLocalTools = disabledLocalTools,
        mcpServers = mcpServers,
        skills = skills,
        slug = slug,
    )

    @Test
    fun `a definition with no surface fields only changes the name`() {
        val derived = definition().toAssistant(parent())
        val original = parent()
        assertEquals("specialist", derived.name)
        assertEquals(original.localTools, derived.localTools)
        assertEquals(original.disabledLocalTools, derived.disabledLocalTools)
        assertEquals(original.mcpServers, derived.mcpServers)
        assertEquals(original.enabledSkills, derived.enabledSkills)
        assertEquals(original.coldMemoryDir, derived.coldMemoryDir)
        assertEquals(original, derived.copy(name = original.name))
    }

    @Test
    fun `the derived assistant keeps the parent id, model, prompt and workspace`() {
        val derived = definition().toAssistant(parent())
        assertEquals(parentId, derived.id)
        assertEquals(parent().chatModelId, derived.chatModelId)
        assertEquals("parent prompt", derived.systemPrompt)
        assertEquals(workspaceId, derived.workspaceId)
        assertTrue(derived.coldMemoryEnabled)
    }

    @Test
    fun `an own local tool list replaces the parent's, and an empty one is not inherit`() {
        assertEquals(
            listOf(LocalToolOption.Browser),
            definition(localTools = listOf(LocalToolOption.Browser)).toAssistant(parent()).localTools,
        )
        assertEquals(emptyList<LocalToolOption>(), definition(localTools = emptyList()).toAssistant(parent()).localTools)
    }

    @Test
    fun `disabled local tools replace rather than merge with the parent's`() {
        val derived = definition(disabledLocalTools = setOf("take_photo")).toAssistant(parent())
        assertEquals(setOf("take_photo"), derived.disabledLocalTools)
        assertEquals(emptySet<String>(), definition(disabledLocalTools = emptySet()).toAssistant(parent()).disabledLocalTools)
    }

    @Test
    fun `mcp server ids are parsed and unparsable ones are dropped`() {
        val derived = definition(
            mcpServers = setOf(mcpB.toString(), "not-a-uuid"),
        ).toAssistant(parent())
        assertEquals(setOf(mcpB), derived.mcpServers)
    }

    @Test
    fun `an empty mcp set means none, not inherit`() {
        assertEquals(emptySet<Uuid>(), definition(mcpServers = emptySet()).toAssistant(parent()).mcpServers)
    }

    @Test
    fun `skills replace the parent's enabled skills`() {
        assertEquals(setOf("a", "b"), definition(skills = setOf("a", "b")).toAssistant(parent()).enabledSkills)
        assertEquals(emptySet<String>(), definition(skills = emptySet()).toAssistant(parent()).enabledSkills)
    }

    @Test
    fun `a slug gives the expert its own cold-memory dir inside the parent workspace`() {
        val derived = definition(slug = "Researcher").toAssistant(parent())
        assertEquals("agents/researcher/memory", derived.coldMemoryDir)
        // Still the parent's workspace — the namespace is a subdirectory, not a new workspace.
        assertEquals(workspaceId, derived.workspaceId)
    }

    @Test
    fun `no slug keeps the parent's cold-memory dir`() {
        assertEquals("parent-memory", definition(slug = null).toAssistant(parent()).coldMemoryDir)
        assertEquals("parent-memory", definition(slug = "  ").toAssistant(parent()).coldMemoryDir)
    }

    @Test
    fun `a blank name keeps the parent's name`() {
        assertEquals("parent", definition(name = "   ").toAssistant(parent()).name)
    }

    @Test
    fun `an empty mcp set does not fall back to the parent's servers`() {
        val derived = definition(mcpServers = emptySet()).toAssistant(parent())
        assertTrue(derived.mcpServers.isEmpty())
        assertNull(derived.mcpServers.firstOrNull { it == mcpA })
    }
}
