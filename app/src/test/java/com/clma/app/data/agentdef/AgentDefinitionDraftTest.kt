package me.rerere.rikkahub.data.agentdef

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.ai.tools.LenientLocalToolListSerializer
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-06c — the pure half of the expert editor.
 *
 * The sheet itself binds a `Text` / `Switch` to this draft and is not unit-tested (no
 * Robolectric in this module), so everything that could be got *wrong* rather than merely
 * *ugly* is pinned here:
 *
 *  - the tri-state: `null` (inherit the parent) and an empty set (own nothing) must survive a
 *    round trip through the editor and must not be confused with each other;
 *  - the namespace: suggestion, normalisation, the "cannot be saved yet" reasons, and the
 *    duplicate check against the other experts (two experts sharing one namespace would mix
 *    their memory files, which is why the sheet refuses rather than warns);
 *  - the catalogue: [LocalToolGroups.all] must name only options this build's serializer knows,
 *    or the tool list would silently lose rows.
 */
class AgentDefinitionDraftTest {

    private fun definition(
        id: String = "def-1",
        name: String = "Researcher",
        slug: String? = null,
        localTools: List<LocalToolOption>? = null,
        disabledLocalTools: Set<String>? = null,
        mcpServers: Set<String>? = null,
        skills: Set<String>? = null,
    ): AgentDefinition = AgentDefinition(
        id = id,
        name = name,
        slug = slug,
        localTools = localTools,
        disabledLocalTools = disabledLocalTools,
        mcpServers = mcpServers,
        skills = skills,
    )

    // ---- inherit vs own ------------------------------------------------------------------

    @Test
    fun `opening a row keeps every inherit exactly as stored`() {
        val row = definition()
        val draft = AgentDefinitionDraft.of(row)

        assertFalse(draft.ownsLocalTools)
        assertFalse(draft.ownsDisabledTools)
        assertFalse(draft.ownsMcpServers)
        assertFalse(draft.ownsSkills)
        assertFalse(draft.namespaceOn)
        assertNull(draft.toDefinition().localTools)
        assertNull(draft.toDefinition().slug)
    }

    @Test
    fun `an empty surface is owned, not inherited, and survives the round trip`() {
        val row = definition(
            localTools = emptyList(),
            disabledLocalTools = emptySet(),
            mcpServers = emptySet(),
            skills = emptySet(),
        )
        val draft = AgentDefinitionDraft.of(row)

        assertTrue(draft.ownsLocalTools)
        assertTrue(draft.ownsDisabledTools)
        assertTrue(draft.ownsMcpServers)
        assertTrue(draft.ownsSkills)

        val stored = draft.toDefinition()
        assertEquals(emptyList<LocalToolOption>(), stored.localTools)
        assertEquals(emptySet<String>(), stored.disabledLocalTools)
        assertEquals(emptySet<String>(), stored.mcpServers)
        assertEquals(emptySet<String>(), stored.skills)
    }

    @Test
    fun `inheriting is reachable only through the explicit reset`() {
        val draft = AgentDefinitionDraft.of(definition(localTools = listOf(LocalToolOption.Battery)))

        val reset = draft.inheritLocalTools()
        assertFalse(reset.ownsLocalTools)
        assertNull(reset.toDefinition().localTools)
    }

    @Test
    fun `toggling a tool group materialises the surface and orders it by the catalogue`() {
        val draft = AgentDefinitionDraft.of(definition())
            .ownLocalTools()
            .toggleLocalTool(LocalToolOption.Workflows, true)
            .toggleLocalTool(LocalToolOption.Battery, true)

        // Catalogue order, not tap order.
        assertEquals(
            listOf(LocalToolOption.Battery, LocalToolOption.Workflows),
            draft.localTools,
        )
    }

    @Test
    fun `toggling the last tool off leaves an owned empty list, never a null`() {
        val draft = AgentDefinitionDraft.of(definition())
            .ownLocalTools()
            .toggleLocalTool(LocalToolOption.Battery, true)
            .toggleLocalTool(LocalToolOption.Battery, false)

        assertTrue(draft.ownsLocalTools)
        assertEquals(emptyList<LocalToolOption>(), draft.localTools)
    }

    @Test
    fun `toggling while still inheriting materialises first instead of dropping the toggle`() {
        val draft = AgentDefinitionDraft.of(definition())
            .toggleLocalTool(LocalToolOption.Battery, true)

        assertTrue(draft.ownsLocalTools)
        assertEquals(listOf(LocalToolOption.Battery), draft.localTools)
    }

    @Test
    fun `the three name-keyed surfaces toggle and sort`() {
        val draft = AgentDefinitionDraft.of(definition())
            .ownDisabledTools()
            .toggleDisabledTool("read_sensor", true)
            .toggleDisabledTool("get_battery_status", true)
            .ownMcpServers()
            .toggleMcpServer("mcp-b", true)
            .toggleMcpServer("mcp-a", true)
            .ownSkills()
            .toggleSkill("zzz", true)
            .toggleSkill("aaa", true)

        assertEquals(setOf("read_sensor", "get_battery_status"), draft.disabledLocalTools)
        assertEquals(setOf("mcp-a", "mcp-b"), draft.mcpServers)
        assertEquals(setOf("aaa", "zzz"), draft.skills)
    }

    // ---- namespace -----------------------------------------------------------------------

    @Test
    fun `the suggested namespace derives from the name and may be empty`() {
        assertEquals("deep-research", AgentDefinitionDraft.of(definition(name = "Deep Research")).suggestedNamespace())
        assertEquals("", AgentDefinitionDraft.of(definition(name = "深度研究")).suggestedNamespace())
    }

    @Test
    fun `the namespace stays off until it is turned on`() {
        val draft = AgentDefinitionDraft.of(definition(name = "Researcher"))
        assertFalse(draft.namespaceOn)
        assertNull(draft.namespaceSlug())
        assertNull(draft.namespacePreview())

        val on = draft.withNamespace(draft.suggestedNamespace())
        assertTrue(on.namespaceOn)
        assertEquals("researcher", on.namespaceSlug())
        assertEquals("agents/researcher/memory", on.namespacePreview())
    }

    @Test
    fun `the raw text is kept while the stored slug is normalised`() {
        val draft = AgentDefinitionDraft.of(definition())
            .withNamespace("  Deep -- Research!!  ")

        // The field echoes what was typed; the row stores the slug.
        assertEquals("  Deep -- Research!!  ", draft.namespaceInput)
        assertEquals("deep-research", draft.namespaceSlug())
        assertEquals("deep-research", draft.toDefinition().slug)
    }

    @Test
    fun `a namespace that normalises to nothing cannot be saved`() {
        val draft = AgentDefinitionDraft.of(definition(name = "深度研究")).withNamespace("深度研究")

        assertEquals(NamespaceProblem.EMPTY, draft.namespaceProblem(emptyList()))
        assertFalse(draft.canSave(emptyList()))
    }

    @Test
    fun `a blank namespace field cannot be saved`() {
        val draft = AgentDefinitionDraft.of(definition()).withNamespace("   ")
        assertEquals(NamespaceProblem.EMPTY, draft.namespaceProblem(emptyList()))
    }

    @Test
    fun `a namespace another expert already owns cannot be saved`() {
        val other = definition(id = "def-2", name = "Other", slug = "researcher")
        val draft = AgentDefinitionDraft.of(definition(id = "def-1", name = "Researcher"))
            .withNamespace("Researcher")

        assertEquals(NamespaceProblem.DUPLICATE, draft.namespaceProblem(listOf(other)))
        assertFalse(draft.canSave(listOf(other)))
    }

    @Test
    fun `a duplicate is detected against the normalised form of the other row`() {
        // The other row's stored slug is padded / mixed case; the comparison is on the slug.
        val other = definition(id = "def-2", name = "Other", slug = "deep-research")
        val draft = AgentDefinitionDraft.of(definition(id = "def-1", name = "Dee"))
            .withNamespace("Deep Research")

        assertEquals(NamespaceProblem.DUPLICATE, draft.namespaceProblem(listOf(other)))
    }

    @Test
    fun `an expert is never its own namespace clash`() {
        val self = definition(id = "def-1", name = "Researcher", slug = "researcher")
        val draft = AgentDefinitionDraft.of(self)

        assertNull(draft.namespaceProblem(listOf(self)))
        assertTrue(draft.canSave(listOf(self)))
    }

    @Test
    fun `a namespace that is off is never a problem`() {
        val other = definition(id = "def-2", name = "Other", slug = "researcher")
        val draft = AgentDefinitionDraft.of(definition(id = "def-1", name = "Researcher"))

        assertNull(draft.namespaceProblem(listOf(other)))
        assertTrue(draft.canSave(listOf(other)))
    }

    // ---- name clash ----------------------------------------------------------------------

    @Test
    fun `a name clash is case-insensitive and never self-inflicted`() {
        val other = definition(id = "def-2", name = "researcher")
        val draft = AgentDefinitionDraft.of(definition(id = "def-1", name = "Researcher"))

        assertTrue(draft.nameClash(listOf(other)))
        assertFalse(draft.nameClash(listOf(definition(id = "def-1", name = "Researcher"))))
        assertTrue(draft.canSave(listOf(definition(id = "def-1", name = "Researcher"))))
    }

    @Test
    fun `an empty name cannot be saved`() {
        val draft = AgentDefinitionDraft.of(definition(name = "   "))
        assertFalse(draft.canSave(emptyList()))
    }

    // ---- storage -------------------------------------------------------------------------

    @Test
    fun `saving trims the text fields and normalises the slug`() {
        val draft = AgentDefinitionDraft.of(definition(name = "  Researcher  "))
            .withNamespace("  Deep -- Research  ")

        val row = draft.toDefinition()
        assertEquals("Researcher", row.name)
        assertEquals("deep-research", row.slug)
    }

    @Test
    fun `a round trip through the editor changes nothing`() {
        val row = definition(
            id = "def-9",
            name = "Researcher",
            slug = "researcher",
            localTools = listOf(LocalToolOption.Battery, LocalToolOption.Workflows),
            disabledLocalTools = setOf("read_sensor"),
            mcpServers = setOf("mcp-a"),
            skills = setOf("skill-a"),
        )

        val again = AgentDefinitionDraft.of(row).toDefinition()
        // `createdAtMs` / `updatedAtMs` are the repository's business and are zero on a draft.
        assertEquals(row.copy(createdAtMs = 0, updatedAtMs = 0), again)
    }

    // ---- card summary --------------------------------------------------------------------

    @Test
    fun `a bare expert has no own surface`() {
        val summary = definition().surfaceSummary()
        assertFalse(summary.hasOwnSurface)
        assertEquals(0, summary.ownGroupCount)
        assertNull(summary.namespace)
    }

    @Test
    fun `the summary counts each owned group and the namespace`() {
        val summary = definition(
            slug = "Researcher",
            localTools = listOf(LocalToolOption.Battery, LocalToolOption.Workflows),
            mcpServers = setOf("mcp-a"),
        ).surfaceSummary()

        assertTrue(summary.hasOwnSurface)
        assertTrue(summary.ownLocalTools)
        assertEquals(2, summary.localToolCount)
        assertFalse(summary.ownsDisabledTools)
        assertTrue(summary.ownsMcpServers)
        assertEquals(1, summary.mcpServerCount)
        assertEquals("researcher", summary.namespace)
        assertEquals(3, summary.ownGroupCount)
    }

    @Test
    fun `an owned empty surface still counts as an own surface`() {
        val summary = definition(localTools = emptyList()).surfaceSummary()
        assertTrue(summary.hasOwnSurface)
        assertTrue(summary.ownLocalTools)
        assertEquals(0, summary.localToolCount)
        assertSame(1, summary.ownGroupCount)
    }

    @Test
    fun `the draft summary agrees with the stored summary`() {
        val row = definition(
            slug = "researcher",
            localTools = listOf(LocalToolOption.Battery),
            skills = setOf("a", "b"),
        )
        assertEquals(row.surfaceSummary(), AgentDefinitionDraft.of(row).surfaceSummary())
    }

    // ---- catalogue -----------------------------------------------------------------------

    @Test
    fun `the catalogue names every group exactly once`() {
        assertEquals(LocalToolGroups.all.size, LocalToolGroups.all.distinct().size)
    }

    @Test
    fun `the catalogue names every tool group this build declares`() {
        // `LocalToolOption` is a sealed class of `data object`s, so every group this build defines
        // is a declared nested class of it. Keeping the hand-written catalogue equal to that set is
        // what stops a newly added tool group from silently missing a row in the expert editor.
        val sealed = LocalToolOption::class.java
        val declared = sealed.declaredClasses
            .filter { it != sealed && sealed.isAssignableFrom(it) }
            .map { it.simpleName }
            .toSet()
        val catalogued = LocalToolGroups.all.map { it::class.java.simpleName }.toSet()

        assertTrue("no tool groups were found by reflection", declared.isNotEmpty())
        assertEquals(declared, catalogued)
    }

    @Test
    fun `every catalogue entry survives the serializer this build actually uses`() {
        // A stale entry (a tool this build no longer defines) would be dropped by the lenient
        // serializer, so a length mismatch is exactly the drift we want to catch.
        val json = Json { encodeDefaults = true }
        val encoded = json.encodeToString(ListSerializer(LocalToolOption.serializer()), LocalToolGroups.all)
        val decoded = json.decodeFromString(LenientLocalToolListSerializer, encoded)

        assertEquals(LocalToolGroups.all, decoded)
    }

    @Test
    fun `the catalogue order is used when the stored list is sorted`() {
        val shuffled = listOf(LocalToolOption.Workflows, LocalToolOption.Battery, LocalToolOption.Workflows)
        assertEquals(
            listOf(LocalToolOption.Battery, LocalToolOption.Workflows),
            LocalToolGroups.order(shuffled),
        )
    }
}
