package com.clma.app.data.ai.tools

import com.clma.ai.provider.BuiltInTools
import com.clma.ai.provider.Model
import com.clma.ai.provider.ProviderSetting
import com.clma.app.data.datastore.Settings
import com.clma.app.data.datastore.findModelById
import com.clma.app.data.model.Assistant
import com.clma.app.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchModeTest {
    @Test
    fun `search picker changes only the conversation assistant and selected model`() {
        val defaultModel = Model(tools = setOf(BuiltInTools.Search))
        val selectedModel = Model(tools = setOf(BuiltInTools.UrlContext))
        val globalAssistant = Assistant(chatModelId = defaultModel.id, enableWebSearch = true)
        val conversationAssistant = Assistant(chatModelId = defaultModel.id, enableWebSearch = false)
        val conversation = Conversation(
            assistantId = conversationAssistant.id,
            chatModelId = selectedModel.id,
            messageNodes = emptyList(),
        )
        val settings = Settings(
            assistantId = globalAssistant.id,
            assistants = listOf(globalAssistant, conversationAssistant),
            providers = listOf(ProviderSetting.Google(models = listOf(defaultModel, selectedModel))),
        )

        val external = settings.withSearchMode(conversation, SearchMode.LOCAL)
        assertTrue(external.assistants.last().enableWebSearch)
        assertSame(settings.providers, external.providers)

        val native = external.withSearchMode(conversation, SearchMode.BUILT_IN)
        assertFalse(native.assistants.last().enableWebSearch)
        assertEquals(selectedModel.tools + BuiltInTools.Search, native.findModelById(selectedModel.id)?.tools)

        val off = native.withSearchMode(conversation, SearchMode.OFF)
        assertEquals(selectedModel, off.findModelById(selectedModel.id))
        assertEquals(defaultModel, off.findModelById(defaultModel.id))
        assertEquals(listOf(globalAssistant, conversationAssistant), off.assistants)
    }

    @Test
    fun `provider override controls eligibility but edits go to owning provider`() {
        val effectiveProvider = ProviderSetting.OpenAI(useResponseApi = false)
        val model = Model(tools = setOf(BuiltInTools.Search), providerOverwrite = effectiveProvider)
        val conversation = Conversation(assistantId = Settings().assistantId, chatModelId = model.id, messageNodes = emptyList())
        val settings = Settings(providers = listOf(ProviderSetting.Google(models = listOf(model))))

        assertSame(settings, settings.withSearchMode(conversation, SearchMode.BUILT_IN))
        val local = settings.withSearchMode(conversation, SearchMode.LOCAL)
        assertEquals(model.copy(tools = emptySet()), local.findModelById(model.id))
        assertTrue(local.assistants.first { it.id == conversation.assistantId }.enableWebSearch)
    }
}
