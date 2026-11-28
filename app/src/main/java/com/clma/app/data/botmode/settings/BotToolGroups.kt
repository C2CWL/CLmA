// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), packages/phone-protocol/src/tool-groups.ts, server/tool-list-budget.ts,
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0
// (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.data.botmode.settings

import com.clma.ai.core.Tool
import com.clma.ai.provider.BuiltInTools
import com.clma.ai.provider.Model
import com.clma.app.data.ai.ContextCompactionPlanner
import com.clma.app.data.botmode.data.BotDataCard
import com.clma.app.data.model.Assistant
import com.clma.app.data.model.isBot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

internal enum class BotToolGroup(val key: String) {
    WEB("web"),
    IMAGES("images"),
    WORKSPACE("workspace"),
    APPS("apps"),
    DATA("data"),
}

private fun groupOf(name: String): BotToolGroup? = when {
    name == BotDataCard.TOOL_NAME -> BotToolGroup.DATA
    name.startsWith("mcp__") -> BotToolGroup.APPS
    name.startsWith("workspace_") -> BotToolGroup.WORKSPACE
    name in setOf("search_web", "scrape_web", "web_fetch", "web_extract") -> BotToolGroup.WEB
    name in setOf("generate_image", "edit_image", "show_image", "conversation_image") -> BotToolGroup.IMAGES
    else -> null
}

private fun groupOf(tool: BuiltInTools): BotToolGroup = when (tool) {
    BuiltInTools.Search, BuiltInTools.UrlContext -> BotToolGroup.WEB
    BuiltInTools.ImageGeneration -> BotToolGroup.IMAGES
}

internal fun Assistant.allowsBotTool(name: String): Boolean =
    !isBot || groupOf(name)?.key !in botToolGroupsOff

internal fun Assistant.filterBotTools(tools: List<Tool>): List<Tool> =
    if (!isBot || botToolGroupsOff.isEmpty()) tools else tools.filter { allowsBotTool(it.name) }

internal fun Assistant.filterBotBuiltIns(model: Model): Model {
    if (!isBot || botToolGroupsOff.isEmpty()) return model
    return model.copy(tools = model.tools.filterNot { groupOf(it).key in botToolGroupsOff }.toSet())
}

internal data class BotToolCost(val count: Int, val tokens: Int)
internal data class BotToolBudget(
    val off: Set<String>,
    val functions: Int,
    val nativeTools: Int,
    val tokens: Int,
    val groups: Map<BotToolGroup, BotToolCost>,
)

internal object BotToolBudgets {
    data class Key(val botId: Uuid, val conversationId: Uuid, val modelId: Uuid)

    private val snapshots = MutableStateFlow<Map<Key, BotToolBudget>>(emptyMap())
    val state = snapshots.asStateFlow()

    fun record(key: Key, assistant: Assistant, model: Model, tools: List<Tool>) {
        val definitions = tools.map { tool ->
            val schema = runCatching { Json.encodeToString(tool.parameters()) }.getOrDefault("")
            val tokens = ContextCompactionPlanner.estimateTokens(
                Json.encodeToString(tool.name) + Json.encodeToString(tool.description) + schema
            )
            groupOf(tool.name) to tokens
        }
        val groups = (definitions + model.tools.map { groupOf(it) to 0 })
            .groupBy({ it.first }, { it.second })
            .mapNotNull { (group, tokens) -> group?.let { it to BotToolCost(tokens.size, tokens.sum()) } }
            .toMap()
        val budget = BotToolBudget(
            assistant.botToolGroupsOff, tools.size, model.tools.size, definitions.sumOf { it.second }, groups,
        )
        snapshots.update { current -> ((current - key) + (key to budget)).entries.toList().takeLast(64)
            .associate { it.key to it.value } }
    }
}
