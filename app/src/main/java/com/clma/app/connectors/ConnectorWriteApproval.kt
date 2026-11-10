package com.clma.app.connectors

import com.clma.ai.core.Tool
import com.clma.ai.ui.UIMessagePart
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

/** In-memory, single-use consent. Persisted Approved states are not capabilities. */
object ConnectorWriteApproval {
    /**
     * Connector writes plus the Bot-mode main assistant's bot-management writes
     * ([com.clma.app.data.botmode.BotMainTools.WRITE_TOOL_NAMES]): both must be approved by the
     * user one call at a time, never by Auto mode, a chat-wide grant or an unattended run.
     */
    val WRITE_TOOL_NAMES: Set<String> = setOf(
        "github_issue_create", "github_comment_create",
    ) + com.clma.app.data.botmode.BotMainTools.WRITE_TOOL_NAMES
    private data class Call(val conversationId: String, val toolCallId: String)
    private data class Consent(val name: String, val args: JsonElement)
    private val consents = ConcurrentHashMap<Call, Consent>()

    fun requiresApproval(name: String): Boolean = name in WRITE_TOOL_NAMES

    fun approveOnce(conversationId: String, toolCallId: String, name: String, args: JsonElement) {
        if (requiresApproval(name)) consents[Call(conversationId, toolCallId)] = Consent(name, args)
    }

    suspend fun isGranted(name: String, args: JsonElement): Boolean {
        val grant = currentCoroutineContext()[Grant] ?: return false
        return grant.name == name && grant.args == args && grant.used.compareAndSet(false, true)
    }

    suspend fun execute(
        tool: Tool,
        args: JsonElement,
        conversationId: String?,
        toolCallId: String,
        approved: Boolean,
        headless: Boolean,
    ): List<UIMessagePart> {
        if (!requiresApproval(tool.name)) return tool.execute(args)
        val consent = conversationId?.let { consents.remove(Call(it, toolCallId)) }
        if (!approved || headless || consent != Consent(tool.name, args)) {
            return listOf(UIMessagePart.Text("""{"error":"connector_explicit_approval_required","recovery":"Request a new call and approve it once in an interactive chat."}"""))
        }
        return withContext(Grant(tool.name, args)) { tool.execute(args) }
    }

    private class Grant(val name: String, val args: JsonElement) : AbstractCoroutineContextElement(Key) {
        val used = AtomicBoolean(false)
        companion object Key : CoroutineContext.Key<Grant>
    }
}
