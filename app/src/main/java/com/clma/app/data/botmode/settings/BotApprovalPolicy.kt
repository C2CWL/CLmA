// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), server/auto-approve.ts, server/always-allow.ts, packages/phone-protocol/src/outbound.ts,
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0
// (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.data.botmode.settings

const val BOT_ALWAYS_ALLOW_MAX = 200

internal fun withAlwaysAllowTool(tools: List<String>, tool: String): List<String>? =
    (tools + tool).distinct().takeIf { it.size <= BOT_ALWAYS_ALLOW_MAX }

private val autoApprovalTools = setOf("search_web", "current_conversation_search")

object BotToolNames {
    private val readVerbs = setOf(
        "get", "list", "fetch", "search", "read", "find", "retrieve", "lookup", "download", "view", "check", "count",
    )
    private val sendVerbs = setOf(
        "send", "sends", "reply", "respond", "forward", "post", "publish", "tweet", "retweet", "broadcast",
        "invite", "share", "refund", "charge", "pay", "payout", "transfer", "buy", "purchase", "dial",
    )
    private val localOnly = setOf("post_notification", "termux_session_send")
    private val knownOutbound = setOf("ssh_upload")
    private val opaqueOutbound = setOf(
        "invoke_api_endpoint", "composio_proxy_execute", "composio_multi_execute_tool",
        "composio_remote_workbench", "composio_remote_bash_tool",
    )
    private val createVerbs = setOf("create", "creation", "add", "new", "make")
    private val sentNouns = setOf(
        "message", "comment", "post", "email", "mail", "invoice", "payment", "refund", "reply", "tweet", "dm", "call",
    )
    private val knownReads = setOf(
        "googlecalendar_events_list", "googlecalendar_events_get", "memory_search", "conversation_search",
        "current_conversation_search", "scrape_web", "web_extract", "x_search", "x_user_posts", "x_post", "x_profile",
    )
    private val humanTools = setOf("ask_user", "askuserquestion", "request_secret")
    private val camelBoundary = Regex("([a-z0-9])([A-Z])")
    private val wordBoundary = Regex("[^a-z0-9]+")
    private val toolkit = Regex("^[A-Z][A-Z0-9]*_[A-Z0-9_]+$")
    private val flatMcp = Regex("^mcp_(?:own_apps|[^_]+)_(.+)$", RegexOption.IGNORE_CASE)

    private fun action(name: String): String =
        if (name.startsWith("mcp__", ignoreCase = true)) {
            name.substringAfter("__").substringAfter("__", "")
        } else {
            flatMcp.matchEntire(name)?.groupValues?.get(1) ?: name
        }

    private fun tokens(action: String): List<String> {
        val words = camelBoundary.replace(action, "$1_$2").lowercase().split(wordBoundary).filter(String::isNotEmpty)
        if (words.firstOrNull() in readVerbs || words.firstOrNull() in sendVerbs ||
            words.firstOrNull() in createVerbs
        ) return words
        return when {
            toolkit.matches(action) -> words.drop(1)
            words.firstOrNull() in setOf("connector", "email", "gmail", "outlook", "slack", "telegram", "discord") ->
                words.drop(1)
            else -> words
        }
    }

    fun asksHuman(name: String): Boolean = action(name).lowercase() in humanTools

    fun isReadOnly(name: String): Boolean {
        val action = action(name)
        return action.lowercase() in knownReads || tokens(action).firstOrNull() in readVerbs
    }

    fun isOpaqueOutbound(name: String): Boolean {
        val action = action(name).lowercase()
        return action in opaqueOutbound || action.endsWith("_composio_proxy_execute")
    }

    fun isOutbound(name: String): Boolean {
        if (name in localOnly) return false
        if (name in knownOutbound || isOpaqueOutbound(name)) return true
        if (isReadOnly(name)) return false
        val words = tokens(action(name))
        return words.any { it in sendVerbs } ||
            ("draft" !in words && words.any { it in createVerbs } && words.any { it in sentNouns })
    }
}

fun botAutoVerdict(
    toolName: String,
    defaults: BotModeSettings,
    profile: BotProfile,
    safetyFloor: Boolean = false,
): Boolean {
    if (safetyFloor || BotToolNames.asksHuman(toolName)) return false
    val level = profile.approvalLevel ?: defaults.approvalLevel
    if (BotToolNames.isOutbound(toolName)) {
        return (profile.outbound ?: defaults.outbound) == BotOutboundPolicy.ALLOW &&
            (!BotToolNames.isOpaqueOutbound(toolName) || level == BotApprovalLevel.FULL)
    }
    if (toolName in profile.alwaysAllow) return true
    return when (level) {
        BotApprovalLevel.ASK -> false
        BotApprovalLevel.AUTO -> toolName in autoApprovalTools
        BotApprovalLevel.FULL -> true
    }
}
