package com.clma.app.service

import com.clma.ai.provider.Model
import com.clma.ai.provider.ModelType
import com.clma.ai.provider.ProviderSetting
import com.clma.app.data.datastore.Settings
import com.clma.app.data.datastore.resolveChatModel
import com.clma.app.data.model.Assistant
import com.clma.app.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * The model ChatService generates with must be the model the chat page shows. The top bar, model
 * chip and send guard read [Settings.resolveChatModel]; generation used its own id-level `?:`
 * chain, so a dangling id made the two disagree (the UI showed one model, the send failed with
 * "No chat model selected", or ran on another).
 */
class ChatModelResolutionParityTest {

    private val grok = Model(modelId = "grok-4.5", displayName = "grok-4.5", type = ModelType.CHAT)
    private val opus = Model(modelId = "claude-opus-4-6", displayName = "claude-opus-4-6", type = ModelType.CHAT)
    private val haiku = Model(modelId = "claude-haiku", displayName = "claude-haiku", type = ModelType.CHAT)
    private val provider = ProviderSetting.OpenAI(name = "P", models = listOf(grok, opus, haiku))

    /** Never belongs to any model: a model that was deleted, or re-added under a fresh UUID. */
    private val danglingId = Uuid.random()

    private fun conversation(assistantId: Uuid, chatModelId: Uuid?) =
        Conversation(assistantId = assistantId, messageNodes = emptyList(), chatModelId = chatModelId)

    private fun settings(owner: Assistant, globalModelId: Uuid) = Settings(
        providers = listOf(provider),
        assistants = listOf(owner),
        assistantId = owner.id,
        chatModelId = globalModelId,
    )

    @Test
    fun `generation resolves exactly what the chat page shows, for every override and default`() {
        val overrides = listOf(null, grok.id, danglingId)
        val assistantModels = listOf(null, opus.id, danglingId)
        val globals = listOf(haiku.id, danglingId)
        for (override in overrides) for (assistantModel in assistantModels) for (global in globals) {
            val current = Assistant(chatModelId = haiku.id)
            val owner = Assistant(chatModelId = assistantModel)
            val s = Settings(
                providers = listOf(provider),
                assistants = listOf(current, owner),
                assistantId = current.id,
                chatModelId = global,
            )
            val conv = conversation(owner.id, override)

            assertEquals(
                "override=$override assistant=$assistantModel global=$global",
                s.resolveChatModel(conv.chatModelId, conv.assistantId),
                resolveGenerationModel(s, conv),
            )
        }
    }

    @Test
    fun `a dangling conversation override falls through to the assistant model instead of failing`() {
        val owner = Assistant(chatModelId = opus.id)
        val s = settings(owner, globalModelId = haiku.id)

        assertEquals(opus, resolveGenerationModel(s, conversation(owner.id, danglingId)))
    }

    @Test
    fun `a routed model wins over the conversation chain for that attempt`() {
        val owner = Assistant(chatModelId = opus.id)
        val s = settings(owner, globalModelId = haiku.id)
        val conv = conversation(owner.id, grok.id)

        assertEquals(haiku, resolveGenerationModel(s, conv, overrideModelId = haiku.id))
        // A routed id that no longer resolves falls back to the normal chain.
        assertEquals(grok, resolveGenerationModel(s, conv, overrideModelId = danglingId))
    }

    @Test
    fun `nothing resolves when every id is dangling`() {
        val owner = Assistant(chatModelId = danglingId)
        val s = settings(owner, globalModelId = danglingId)

        assertNull(resolveGenerationModel(s, conversation(owner.id, danglingId)))
    }
    @Test
    fun `peer can inherit requester model when target defaults are absent or deleted`() {
        for (targetModel in listOf(null, danglingId)) {
            val owner = Assistant(chatModelId = targetModel)
            val s = settings(owner, globalModelId = danglingId)
            val conv = conversation(owner.id, null)

            assertNull(resolveGenerationModel(s, conv))
            assertEquals(grok, resolveGenerationModel(s, conv, peerFallbackModelId = grok.id))
            assertNull(conv.chatModelId)
            assertEquals(targetModel, owner.chatModelId)
        }
    }

    @Test
    fun `peer fallback never replaces target conversation assistant global or routed models`() {
        val owner = Assistant(chatModelId = opus.id)
        val s = settings(owner, globalModelId = haiku.id)
        assertEquals(opus, resolveGenerationModel(s, conversation(owner.id, null), peerFallbackModelId = grok.id))
        assertEquals(haiku, resolveGenerationModel(s, conversation(owner.id, haiku.id), peerFallbackModelId = grok.id))
        assertEquals(haiku, resolveGenerationModel(s.copy(assistants = listOf(owner.copy(chatModelId = null))),
            conversation(owner.id, null), peerFallbackModelId = grok.id))
        assertEquals(haiku, resolveGenerationModel(s, conversation(owner.id, null),
            overrideModelId = haiku.id, peerFallbackModelId = grok.id))
    }

    @Test
    fun `peer fallback is resolved anew and missing fallback still fails closed`() {
        val owner = Assistant()
        val s = settings(owner, globalModelId = danglingId)
        val conv = conversation(owner.id, null)
        assertEquals(grok, resolveGenerationModel(s, conv, peerFallbackModelId = grok.id))
        assertEquals(opus, resolveGenerationModel(s, conv, peerFallbackModelId = opus.id))
        assertNull(resolveGenerationModel(s, conv, peerFallbackModelId = danglingId))
        assertNull(resolveGenerationModel(s, conv))
    }

    @Test
    fun `explicit disabled target is not replaced by peer fallback`() {
        val owner = Assistant(chatModelId = opus.id)
        val s = settings(owner, globalModelId = danglingId).copy(providers = listOf(provider.copy(enabled = false)))
        assertEquals(opus, resolveGenerationModel(s, conversation(owner.id, null), peerFallbackModelId = grok.id))
    }

}
