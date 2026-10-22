package me.rerere.rikkahub.data.agentdef

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-06 — `subagent_dispatch`'s `agent` parameter resolves a stored [AgentDefinition] by name.
 *
 * Ported from `SubAgentProfileResolverTest` unchanged in spirit: the resolver's contract is
 * what #36 and #28 were about, and it must survive the move from DataStore profiles to the
 * Room-backed expert library. Pins exact / case-insensitive match, loud failure (never silent
 * parent-model inheritance) on an unknown or disabled name, and that disabled definitions are
 * invisible to resolution entirely.
 */
class AgentDefinitionResolverTest {

    private val researcher = AgentDefinition(
        id = "id-researcher",
        name = "Researcher",
        description = "Looks things up.",
        systemPrompt = "You are a careful researcher.",
        enabled = true,
    )
    private val disabled = AgentDefinition(
        id = "id-retired",
        name = "Retired",
        description = "No longer used.",
        systemPrompt = "You are retired.",
        enabled = false,
    )
    private val definitions = listOf(researcher, disabled)

    @Test
    fun `null agent is not requested`() {
        val result = AgentDefinitionResolver.resolve(null, definitions)
        assertTrue(result is AgentDefinitionResolver.Result.NotRequested)
    }

    @Test
    fun `blank agent is not requested`() {
        val result = AgentDefinitionResolver.resolve("   ", definitions)
        assertTrue(result is AgentDefinitionResolver.Result.NotRequested)
    }

    @Test
    fun `an exact name resolves`() {
        val resolved = AgentDefinitionResolver.resolve("Researcher", definitions)
            as AgentDefinitionResolver.Result.Resolved
        assertEquals(researcher, resolved.definition)
    }

    @Test
    fun `matching is case-insensitive`() {
        val resolved = AgentDefinitionResolver.resolve("rEsEaRcHeR", definitions)
            as AgentDefinitionResolver.Result.Resolved
        assertEquals(researcher, resolved.definition)
    }

    @Test
    fun `an unknown name fails and lists what is available`() {
        val failed = AgentDefinitionResolver.resolve("no-such-agent", definitions)
            as AgentDefinitionResolver.Result.Failed
        assertTrue(failed.message.contains("no-such-agent"))
        assertTrue(failed.message.contains("Researcher"))
    }

    @Test
    fun `a disabled name is not resolvable and is not listed`() {
        val failed = AgentDefinitionResolver.resolve("Retired", definitions)
            as AgentDefinitionResolver.Result.Failed
        assertTrue(failed.message.contains("Retired"))
        // The disabled expert must not be advertised as a valid target.
        assertTrue(!failed.message.contains("Available: Retired"))
    }

    @Test
    fun `two enabled definitions sharing a name are ambiguous`() {
        val duplicate = researcher.copy(id = "id-researcher-2")
        val failed = AgentDefinitionResolver.resolve("Researcher", listOf(researcher, duplicate))
            as AgentDefinitionResolver.Result.Failed
        assertTrue(failed.message.contains("multiple experts"))
        assertTrue(failed.message.contains("duplicates"))
    }

    @Test
    fun `an empty library reports that nothing is configured`() {
        val failed = AgentDefinitionResolver.resolve("anything", emptyList())
            as AgentDefinitionResolver.Result.Failed
        assertTrue(failed.message.contains("no experts are configured"))
    }

    @Test
    fun `enabledDefinitions drops only the disabled ones and keeps order`() {
        assertEquals(listOf(researcher), AgentDefinitionResolver.enabledDefinitions(definitions))
    }
}
