package com.clma.app.data.ai.tools

import com.clma.ai.provider.BuiltInTools
import com.clma.ai.provider.supportsNativeWebSearch
import com.clma.app.data.ai.AssistantResolver
import com.clma.app.data.datastore.Settings
import com.clma.app.data.datastore.findProvider
import com.clma.app.data.datastore.resolveChatModel
import com.clma.app.data.model.Conversation

enum class SearchMode {
    OFF,
    LOCAL,
    BUILT_IN,
}

internal fun Settings.withSearchMode(conversation: Conversation, mode: SearchMode): Settings {
    val assistant = AssistantResolver.forConversation(this, conversation.assistantId).assistant
    val model = resolveChatModel(conversation.chatModelId, conversation.assistantId)
    val provider = model?.findProvider(providers)
    if (mode == SearchMode.BUILT_IN && (provider?.enabled != true || !provider.supportsNativeWebSearch())) {
        return this
    }
    val storedProvider = model?.findProvider(providers, checkOverwrite = false)
    val updatedTools = model?.tools?.let {
        if (mode == SearchMode.BUILT_IN) it + BuiltInTools.Search else it - BuiltInTools.Search
    }
    return copy(
        assistants = assistants.map {
            if (it.id == assistant.id) it.copy(enableWebSearch = mode == SearchMode.LOCAL) else it
        },
        providers = if (model != null && updatedTools != null && updatedTools != model.tools) {
            providers.map {
                if (it === storedProvider) it.editModel(model.copy(tools = updatedTools)) else it
            }
        } else {
            providers
        },
    )
}
