package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/**
 * Outcome of a model-initiated context compaction (T-02 / ②).
 *
 * [tokensBefore] / [tokensAfter] are local estimates of the *request* context (see
 * `ContextBudgetPlanner.estimateContextTokens`), not provider-reported usage — they exist so the
 * model can tell whether asking for a compaction was worth a whole summariser round-trip. They
 * are deliberately allowed to be equal or even slightly larger after the call (a very short
 * conversation, or a verbose summary): the tool reports what happened instead of pretending.
 *
 * This type is plain data with no Android or IO dependencies so it can be unit tested directly.
 */
data class CompactionToolResult(
    val compacted: Boolean,
    val tokensBefore: Int,
    val tokensAfter: Int,
    val summaryChars: Int,
    val note: String = "",
) {
    /** Renders the tool's JSON result envelope. Field names are part of the contract (see PR docs). */
    fun toJson(): String = Json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("compacted", compacted)
            put("tokensBefore", tokensBefore)
            put("tokensAfter", tokensAfter)
            put("summaryChars", summaryChars)
            if (note.isNotEmpty()) {
                put("note", note)
            }
        }
    )
}

/**
 * T-02 / ② — builds the optional `compact_context` tool.
 *
 * The tool itself owns **no** summarisation logic: [onCompact] is injected by the caller
 * (`ChatService`), which routes it to the exact same pipeline the manual "compress context"
 * action uses. This function is therefore pure wiring + argument plumbing, and can be unit
 * tested with a fake [onCompact].
 *
 * Note this is intentionally *not* named `createCompactionTools` to match the neighbouring
 * factory helpers (`createSearchTools`, `createSkillTools`) — keep the naming style consistent
 * when adding more tools here.
 *
 * @param onCompact receives the optional free-text `instructions` argument, already trimmed and
 *   normalised to `null` when the model omitted it or sent only whitespace. Returning normally
 *   produces a success envelope; throwing produces a `tool_failed` envelope upstream.
 */
fun buildCompactionTools(
    onCompact: suspend (instructions: String?) -> CompactionToolResult,
): List<Tool> = listOf(
    Tool(
        name = "compact_context",
        description = """
            Summarise this conversation's earlier history in place, so later turns carry less
            context. Use it when the context has grown long and you are finished with the exact
            wording of the earlier turns.

            What happens: everything before a retained recent tail is replaced by a
            model-written summary. Original messages are NOT deleted — only what gets sent to
            the model is shortened. The shortened context takes effect from the NEXT turn; the
            turn you are currently in keeps the raw history either way.

            Call it when:
            - the context is dominated by very long tool outputs or old turns you are done with;
            - you are about to start a long multi-step task and want headroom first.

            Do not call it when:
            - the current task still depends on exact text from earlier turns (file paths, ids,
              error messages, command output) — after compaction only the summary remains in
              context, and re-running a command can be far more expensive than the context saved;
            - you compacted very recently, or there is little history left. A compaction costs a
              full extra model call, so doing it twice in a row is pure waste;
            - the user explicitly asked you to work from the full history verbatim.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("instructions", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Optional. Free-text guidance on what the summary must preserve, " +
                                "e.g. \"keep the file paths and the failing command\". " +
                                "Omit to use the default summariser prompt."
                        )
                    })
                },
                required = emptyList(),
            )
        },
        execute = {
            val instructions = it.jsonObject["instructions"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.trim()
                ?.takeIf { text -> text.isNotEmpty() }
            listOf(UIMessagePart.Text(onCompact(instructions).toJson()))
        },
    )
)
