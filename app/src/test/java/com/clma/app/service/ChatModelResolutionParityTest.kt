package me.rerere.rikkahub.service

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.resolveChatModel
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
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
}
