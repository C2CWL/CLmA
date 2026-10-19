package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Locks down the chat-model resolution the send guard runs on. The guard used to read only
 * `assistant.chatModelId ?: settings.chatModelId`, so a conversation whose own override was valid
 * but whose assistant default pointed at a deleted model got "select a model first" while the
 * picker highlighted the override.
 */
class ResolveChatModelTest {

    private val grok = Model(modelId = "grok-4.5", displayName = "grok-4.5", type = ModelType.CHAT)
    private val opus = Model(modelId = "claude-opus-4-6", displayName = "claude-opus-4-6", type = ModelType.CHAT)
    private val haiku = Model(modelId = "claude-haiku", displayName = "claude-haiku", type = ModelType.CHAT)
    private val provider = ProviderSetting.OpenAI(name = "P", models = listOf(grok, opus, haiku))

    /** Never belongs to any model: a model that was deleted, or re-added under a fresh UUID. */
    private val danglingId = Uuid.random()

    private fun settings(
        assistants: List<Assistant>,
        currentAssistantId: Uuid = assistants.first().id,
        globalModelId: Uuid = danglingId,
        providers: List<ProviderSetting> = listOf(provider),
    ) = Settings(
        providers = providers,
        assistants = assistants,
        assistantId = currentAssistantId,
        chatModelId = globalModelId,
    )

    @Test
    fun `conversation override wins even when assistant and global defaults are dangling`() {
        val assistant = Assistant(chatModelId = danglingId)
        val s = settings(listOf(assistant))

        // The old guard's source: nothing resolves, so it reported "select a model first".
        assertNull(s.getCurrentChatModel())
        assertEquals(grok, s.resolveChatModel(grok.id, assistant.id))
    }

    @Test
    fun `no override falls back to the assistant model, then the global default`() {
        val withModel = Assistant(chatModelId = opus.id)
        val withoutModel = Assistant(chatModelId = null)
        val s = settings(listOf(withModel, withoutModel), globalModelId = haiku.id)

        assertEquals(opus, s.resolveChatModel(null, withModel.id))
        assertEquals(haiku, s.resolveChatModel(null, withoutModel.id))
    }

    @Test
    fun `a dangling id is skipped instead of ending the chain`() {
        val assistant = Assistant(chatModelId = danglingId)
        val s = settings(listOf(assistant), globalModelId = haiku.id)

        // Dangling assistant default → global default.
        assertEquals(haiku, s.resolveChatModel(null, assistant.id))
        // Dangling conversation override → assistant default.
        val healthy = Assistant(chatModelId = opus.id)
        assertEquals(opus, settings(listOf(healthy)).resolveChatModel(danglingId, healthy.id))
    }

    @Test
    fun `uses the conversation's assistant, not the global current-assistant pointer`() {
        val current = Assistant(chatModelId = opus.id)
        val conversationOwner = Assistant(chatModelId = grok.id)
        val s = settings(listOf(current, conversationOwner), currentAssistantId = current.id)

        assertEquals(grok, s.resolveChatModel(null, conversationOwner.id))
    }

    @Test
    fun `an unknown or missing assistant id falls back to the global current assistant`() {
        val current = Assistant(chatModelId = opus.id)
        val s = settings(listOf(current))

        assertEquals(opus, s.resolveChatModel(null, Uuid.random()))
        assertEquals(opus, s.resolveChatModel(null, null))
    }

    @Test
    fun `nothing resolves when every id is dangling, so the guard still fires`() {
        val assistant = Assistant(chatModelId = danglingId)
        val s = settings(listOf(assistant))

        assertNull(s.resolveChatModel(danglingId, assistant.id))
        assertNull(settings(listOf(assistant), providers = emptyList()).resolveChatModel(grok.id, assistant.id))
    }
}
