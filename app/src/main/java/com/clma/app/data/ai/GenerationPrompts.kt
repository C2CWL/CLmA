package com.clma.app.data.ai

import com.clma.app.data.model.AssistantMemory

internal fun buildMemoryPrompt(memories: List<AssistantMemory>) =
    com.clma.app.data.memory.MemoryPromptRenderer.hot(memories.map { it.id to it.content })
