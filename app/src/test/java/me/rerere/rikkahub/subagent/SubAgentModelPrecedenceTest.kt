package me.rerere.rikkahub.subagent

import me.rerere.rikkahub.data.agentdef.AgentDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * #36, re-homed onto the expert library by P2-06b — the `model_id` vs expert-model precedence rule.
 *
 * Ported verbatim in spirit from the `resolveSubAgentModel` half of the retired
 * `SubAgentProfileResolverTest`: `model_id` always wins when it was given (and a FAILED explicit
 * `model_id` must surface, never be papered over by falling back to the expert's model); only when
 * `model_id` was absent does the expert's model get a chance. `AgentDefinition.modelId` is the
 * canonical `Uuid` string, so the fallback parses it — an unparseable stored value falls back to
 * inheriting rather than failing the dispatch.
 */
class SubAgentModelPrecedenceTest {

    private val researcher = AgentDefinition(id = "def-1", name = "Researcher")

    @Test
    fun `model_id wins over the expert's model when both resolve`() {
        val explicitModelId = Uuid.random()
        val expertModelId = Uuid.random()
        val expert = researcher.copy(modelId = expertModelId.toString())

        val combined = resolveSubAgentModel(
            SubAgentModelResolver.Result.Resolved(explicitModelId),
            expert,
        )

        assertEquals(SubAgentModelResolver.Result.Resolved(explicitModelId), combined)
    }

    @Test
    fun `a failed model_id is not papered over by the expert's model`() {
        val expert = researcher.copy(modelId = Uuid.random().toString())
        val failure = SubAgentModelResolver.Result.Failed("model_id \"bogus\" did not match any model")

        val combined = resolveSubAgentModel(failure, expert)

        assertEquals(failure, combined)
    }

    @Test
    fun `falls back to the expert's model when model_id was not given`() {
        val expertModelId = Uuid.random()
        val expert = researcher.copy(modelId = expertModelId.toString())

        val combined = resolveSubAgentModel(SubAgentModelResolver.Result.Inherit, expert)

        assertEquals(SubAgentModelResolver.Result.Resolved(expertModelId), combined)
    }

    @Test
    fun `no expert and no model_id leaves the tool working exactly as before`() {
        val combined = resolveSubAgentModel(SubAgentModelResolver.Result.Inherit, definition = null)

        assertTrue(combined is SubAgentModelResolver.Result.Inherit)
    }

    @Test
    fun `an expert with no model set also inherits, exactly as before`() {
        val expert = researcher.copy(modelId = null)

        val combined = resolveSubAgentModel(SubAgentModelResolver.Result.Inherit, expert)

        assertTrue(combined is SubAgentModelResolver.Result.Inherit)
    }

    @Test
    fun `an unparseable stored model id degrades to inheriting rather than failing`() {
        // Hand-edited data only. A dispatch must not die because one expert's model column got
        // garbled — inheriting the parent's model is the same answer an expert with no model gives.
        val expert = researcher.copy(modelId = "not-a-uuid")

        val combined = resolveSubAgentModel(SubAgentModelResolver.Result.Inherit, expert)

        assertTrue(combined is SubAgentModelResolver.Result.Inherit)
    }

    // — assistant-level default (the "run my sub-agents on this cheaper model" setting) —

    @Test
    fun `the assistant default applies when neither model_id nor the expert pins a model`() {
        val assistantModel = Uuid.random()

        val combined = resolveSubAgentModel(
            SubAgentModelResolver.Result.Inherit,
            definition = null,
            assistantSubAgentModelId = assistantModel,
        )

        assertEquals(SubAgentModelResolver.Result.Resolved(assistantModel), combined)
    }

    @Test
    fun `the expert's model wins over the assistant default`() {
        val expertModelId = Uuid.random()
        val expert = researcher.copy(modelId = expertModelId.toString())

        val combined = resolveSubAgentModel(
            SubAgentModelResolver.Result.Inherit,
            expert,
            assistantSubAgentModelId = Uuid.random(),
        )

        assertEquals(SubAgentModelResolver.Result.Resolved(expertModelId), combined)
    }

    @Test
    fun `an explicit model_id wins over the assistant default`() {
        val explicitModelId = Uuid.random()

        val combined = resolveSubAgentModel(
            SubAgentModelResolver.Result.Resolved(explicitModelId),
            definition = null,
            assistantSubAgentModelId = Uuid.random(),
        )

        assertEquals(SubAgentModelResolver.Result.Resolved(explicitModelId), combined)
    }

    @Test
    fun `an unparseable expert model falls through to the assistant default`() {
        val assistantModel = Uuid.random()
        val expert = researcher.copy(modelId = "not-a-uuid")

        val combined = resolveSubAgentModel(
            SubAgentModelResolver.Result.Inherit,
            expert,
            assistantSubAgentModelId = assistantModel,
        )

        assertEquals(SubAgentModelResolver.Result.Resolved(assistantModel), combined)
    }
}
