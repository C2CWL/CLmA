package com.clma.app.data.ai.tools

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.uuid.Uuid

/**
 * In-memory "Allow for this chat" allow-list. Holds (conversationId, toolName) entries
 * granted by the user clicking "Allow for this chat" on a tool-approval prompt. Resets on:
 *   - /new in Telegram (handleResetCommand calls clearChat)
 *   - Starting a fresh in-app chat (ChatService can call clearChat on new conversation)
 *   - App process restart (this object is process-local)
 *
 * There is no persistent counterpart any more: the blacklist in [ToolApprovalPreferences]
 * (DataStore-backed) is where a tool stops asking for good, so a per-chat grant is the only
 * shortcut, and it dies with the conversation.
 *
 * Grants are created two ways: tapping "Allow for this chat" on an approval card, or presetting
 * them in advance from the in-chat "本对话授权" sheet. Both land here, keyed by conversation id,
 * so a brand-new chat that has not been saved yet works too (its id exists from navigation).
 * [version] ticks on every change so Compose surfaces can re-read [listForChat].
 */
object ToolApprovalAllowList {

    private val perChat: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    private val _version = MutableStateFlow(0L)

    /** Monotonic change counter: bumps whenever any grant is added or removed. */
    val version: StateFlow<Long> = _version.asStateFlow()

    private fun bump() {
        _version.update { it + 1 }
    }

    private fun key(conversationId: Uuid, toolName: String) = "${conversationId}::${toolName}"

    /** True iff "Allow for this chat" has been granted for [toolName] in [conversationId]. */
    fun isAllowedForChat(conversationId: Uuid, toolName: String): Boolean =
        perChat.contains(key(conversationId, toolName))

    /** Record an "Allow for this chat" grant. */
    fun grantForChat(conversationId: Uuid, toolName: String) {
        if (toolName.isBlank()) return
        if (perChat.add(key(conversationId, toolName))) bump()
    }

    /** Record grants for every name in [toolNames] at once (the sheet's "allow all"). Blank
     *  names are skipped. Returns how many were newly added; [version] bumps once if any. */
    fun grantAllForChat(conversationId: Uuid, toolNames: Collection<String>): Int {
        var added = 0
        for (name in toolNames) {
            if (name.isNotBlank() && perChat.add(key(conversationId, name))) added++
        }
        if (added > 0) bump()
        return added
    }

    /** Drop every per-chat grant for the given conversation. Used by /new and the fresh-
     *  chat reset path so a new conversation starts with a clean slate. */
    fun clearChat(conversationId: Uuid) {
        val prefix = "${conversationId}::"
        if (perChat.removeIf { it.startsWith(prefix) }) bump()
    }

    /** Revoke a single (conversation, tool) grant — without blowing away the rest of the
     *  conversation's allow-list. Used by the in-chat "revoke" affordance so the user can
     *  back out of an accidental "Allow for this chat" tap on a destructive tool without
     *  having to start a new conversation and lose context. */
    fun revokeForChat(conversationId: Uuid, toolName: String) {
        if (perChat.remove(key(conversationId, toolName))) bump()
    }

    /** Snapshot of every grant for [conversationId]. Used by the "what have I allowed?"
     *  UI surface so it can render one row per active grant + a revoke button. */
    fun listForChat(conversationId: Uuid): List<String> {
        val prefix = "${conversationId}::"
        return perChat.asSequence()
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .sorted()
            .toList()
    }
}
