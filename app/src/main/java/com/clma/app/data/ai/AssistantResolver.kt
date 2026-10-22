package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import kotlin.uuid.Uuid

/**
 * P2-01 — the single assistant-resolution point.
 *
 * ## Why
 *
 * "Which assistant does this work belong to?" used to be answered by hand at every call site.
 * Most sites asked the conversation, some fell back to the global current-assistant pointer,
 * and the workflow engine had its own persisted-authoring-id → toggle-scan fallback. The
 * lookups looked interchangeable but were not: `getAssistantById` returned null while
 * `getAssistantById(id) ?: getCurrentAssistant()` silently substituted a *different*
 * assistant, and only some callers had the fallback. A mid-turn assistant switch, a deleted
 * assistant, or a legacy null id therefore resolved differently depending on which line ran.
 *
 * Every lookup now goes through the functions below. The core overloads take the raw
 * `assistants` list so they are unit-testable without building a whole [Settings]; the
 * [Settings] overloads are conveniences, and the `Settings.getAssistantById` /
 * `Settings.getCurrentAssistant` / `Settings.findAssistantById` extensions in
 * `PreferencesStore` delegate here too, so UI code resolves through the same implementation.
 *
 * ## Forward looking (P2-04 / P2-06)
 *
 * [Source] records *where* an assistant came from. When expert definitions land, resolution
 * grows an `AGENT_DEFINITION` branch that synthesises an [Assistant] from a stored definition,
 * and consumers can branch on the source instead of re-deriving the reason for a fallback.
 */
object AssistantResolver {

    /** Why the returned [Assistant] is the one it is. */
    enum class Source {
        /** The id we were asked about resolves. */
        REQUESTED,

        /** The asked-about id is unknown → the global current-assistant pointer was used. */
        GLOBAL_CURRENT,

        /** Workflow fire: the persisted authoring assistant still exists. */
        WORKFLOW_AUTHOR,

        /** Workflow fire: authoring assistant gone or legacy → first assistant with Workflows. */
        WORKFLOW_TOGGLE_FALLBACK,

        /** P2-06: synthesised from a stored `AgentDefinition` rather than a stored assistant. */
        AGENT_DEFINITION,
    }

    data class Resolution(val assistant: Assistant, val source: Source)

    /** The assistant with this id, or `null` when it does not exist. */
    fun byId(assistants: List<Assistant>, id: Uuid?): Assistant? =
        assistants.firstOrNull { it.id == id }

    /**
     * The global "current" assistant: whatever the app-level pointer names, else the first
     * assistant. Throws on an empty list — the same `assistants.first()` the callers used to
     * do inline.
     */
    fun current(assistants: List<Assistant>, currentAssistantId: Uuid?): Assistant =
        assistants.find { it.id == currentAssistantId } ?: assistants.first()

    /**
     * The assistant a conversation belongs to, falling back to the global pointer when the
     * conversation names an assistant that no longer exists (deleted while the turn was
     * queued). The fallback is reported through [Resolution.source] rather than being
     * invisible.
     */
    fun forConversation(
        assistants: List<Assistant>,
        conversationAssistantId: Uuid,
        currentAssistantId: Uuid?,
    ): Resolution {
        val requested = byId(assistants, conversationAssistantId)
        return if (requested != null) {
            Resolution(requested, Source.REQUESTED)
        } else {
            Resolution(current(assistants, currentAssistantId), Source.GLOBAL_CURRENT)
        }
    }

    /**
     * The assistant a workflow fire executes against: the persisted authoring assistant id
     * when it still resolves, else "the first assistant with the Workflows tool on". `null`
     * when neither exists — the engine then records `no_workflows_assistant`.
     *
     * Note the authoring-id branch is only taken when the id was **stored**; a legacy `null`
     * id goes straight to the toggle scan, exactly as before.
     */
    fun forWorkflow(assistants: List<Assistant>, authoringAssistantId: String?): Resolution? {
        val stored = authoringAssistantId
            ?.let { id -> assistants.firstOrNull { it.id.toString() == id } }
        if (stored != null) return Resolution(stored, Source.WORKFLOW_AUTHOR)
        return assistants
            .firstOrNull { asst -> asst.localTools.any { it is LocalToolOption.Workflows } }
            ?.let { Resolution(it, Source.WORKFLOW_TOGGLE_FALLBACK) }
    }

    // ---- Settings-flavoured conveniences (one-liners over the cores above) -------------

    fun byId(settings: Settings, id: Uuid?): Assistant? = byId(settings.assistants, id)

    fun current(settings: Settings): Assistant = current(settings.assistants, settings.assistantId)

    fun forConversation(settings: Settings, conversationAssistantId: Uuid): Resolution =
        forConversation(settings.assistants, conversationAssistantId, settings.assistantId)

    fun forWorkflow(settings: Settings, authoringAssistantId: String?): Resolution? =
        forWorkflow(settings.assistants, authoringAssistantId)
}
