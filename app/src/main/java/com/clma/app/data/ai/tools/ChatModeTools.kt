package com.clma.app.data.ai.tools

import com.clma.ai.core.Tool
import com.clma.ai.provider.BuiltInTools

/**
 * Chat-mode tool allowlist (conversation.agentModeEnabled == false).
 *
 * Chat mode only *removes* tools; it never enables a tool that its normal gates would
 * leave off (assistant toggles, Search settings, enableMemory, enableRecentChatsReference,
 * enableCompactContextTool, LocalToolOption groups, model BuiltInTools membership, …).
 *
 * Applied at [com.clma.app.service.ChatService.assembleChatToolSurface] (direct surface,
 * no progressive catalog / MCP) and again in [com.clma.app.data.ai.GenerationLoop] so the
 * separately-injected `memory_tool` path cannot leak non-allowlisted names.
 */
object ChatModeTools {

    /**
     * Exact tool names the model may see in chat mode. Everything else is stripped.
     * Progressive catalog meta-tools (`tool_search` / `tool_browse` / `tool_open` /
     * `tool_close`) are intentionally absent: allowlisted tools are attached directly.
     */
    val ALLOWED_TOOL_NAMES: Set<String> = setOf(
        // App web search (SearchTools + Search-settings web_fetch/extract)
        "search_web",
        "scrape_web",
        "web_fetch",
        "web_extract",
        // Hot memory (GenerationLoop / ChatToolFactory path)
        "memory_tool",
        // Light local utilities (LocalToolOption.TimeInfo / AskUser / Tts / Clipboard)
        "get_time_info",
        "ask_user",
        "text_to_speech",
        "clipboard_tool",
        // App image / video generation (LocalToolOption.ImageGeneration / VideoGeneration)
        "generate_image",
        "edit_image",
        "generate_video",
        // Chat history search
        "recent_chats",
        "conversation_search",
        "current_conversation_search",
        // Context compaction
        "compact_context",
        // Cost / token budget read
        "check_token_usage",
    )

    /** Provider-native built-ins kept on [com.clma.ai.provider.Model.tools] in chat mode. */
    val ALLOWED_BUILT_INS: Set<BuiltInTools> = setOf(
        BuiltInTools.Search,
        BuiltInTools.UrlContext,
        BuiltInTools.ImageGeneration,
    )

    fun isAllowed(toolName: String): Boolean = toolName in ALLOWED_TOOL_NAMES

    fun filterTools(tools: List<Tool>): List<Tool> = tools.filter { it.name in ALLOWED_TOOL_NAMES }

    fun filterBuiltIns(tools: Set<BuiltInTools>): Set<BuiltInTools> =
        tools.filter { it in ALLOWED_BUILT_INS }.toSet()
}
