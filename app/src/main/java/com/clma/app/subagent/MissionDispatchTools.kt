package com.clma.app.subagent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.clma.ai.core.InputSchema
import com.clma.ai.core.Tool
import com.clma.ai.ui.UIMessagePart
import com.clma.app.data.agentdef.AgentDefinition
import com.clma.app.data.agentdef.AgentDefinitionResolver
import com.clma.app.data.ai.tools.ToolInvocationContext

/**
 * Mission dispatch — one call that fans a decomposable job out to several sub-agents at once.
 *
 * `subagent_dispatch` takes a single task and blocks until it is terminal, so a parent that wants
 * three independent answers has to serialise them (or fire background runs and lose the result).
 * This tool is the batch form of the same engine call: N tasks in, N terminal runs out, aggregated
 * into one envelope. Everything below `dispatch` is unchanged — same validator, same concurrency
 * caps, same recursion guard, same ledger — so this adds a shape, not a second delegation path.
 *
 * Hard limits, deliberately compiled in rather than model-controlled:
 * - 1..6 tasks per call. A rejected count fails the whole call instead of silently truncating, so
 *   the model never believes it dispatched work it did not.
 * - `max_concurrency` 1..6, default 3. It narrows the fan-out below the call; it can never raise
 *   it above the assistant's own cap, which the engine still enforces.
 * - No `assistant_id`, no `kind`, no per-task model or tool surface: every child inherits the
 *   caller's assistant, runs as a foreground sub-agent, and gets the same headless-frozen surface
 *   a `subagent_dispatch` child would. Nothing here can widen a child's authority.
 * - Refused outright from a headless run (cron / workflow / another sub-agent), the same
 *   Fork Bomb guard `subagent_dispatch` carries.
 *
 * Results are excerpts, not transcripts: each entry is capped at [MissionLimits.RESULT_EXCERPT_CHARS]
 * characters and names its run id, so the child's full text stays one `subagent_get` away instead
 * of landing in the parent's context six times over.
 */

internal object MissionLimits {
    const val MIN_TASKS = 1
    const val MAX_TASKS = 6
    const val DEFAULT_MAX_CONCURRENCY = 3
    const val MAX_MAX_CONCURRENCY = 6
    const val RESULT_EXCERPT_CHARS = 1200
}

/** One fanned-out task, already normalised (trimmed, blank fields dropped). */
internal data class MissionTask(
    val prompt: String,
    val title: String? = null,
    val agent: String? = null,
)

internal sealed class MissionTaskParseResult {
    data class Ok(val tasks: List<MissionTask>) : MissionTaskParseResult()
    data class Reject(val error: String, val detail: String) : MissionTaskParseResult()
}

/**
 * Reads `tasks` strictly: an entry without a non-blank `prompt` fails the call rather than being
 * skipped, because a partially-executed mission is the hardest kind to reason about afterwards.
 */
internal fun parseMissionTasks(raw: JsonElement?): MissionTaskParseResult {
    val array = raw as? JsonArray
        ?: return MissionTaskParseResult.Reject(
            "invalid_tasks",
            "tasks must be an array of ${MissionLimits.MIN_TASKS}-${MissionLimits.MAX_TASKS} objects, " +
                "each carrying a `prompt`.",
        )
    if (array.size < MissionLimits.MIN_TASKS || array.size > MissionLimits.MAX_TASKS) {
        return MissionTaskParseResult.Reject(
            "invalid_tasks",
            "tasks must contain ${MissionLimits.MIN_TASKS}-${MissionLimits.MAX_TASKS} entries; " +
                "got ${array.size}. Split the work, or use subagent_dispatch for a single task.",
        )
    }
    val tasks = ArrayList<MissionTask>(array.size)
    for ((index, entry) in array.withIndex()) {
        val obj = entry as? JsonObject
            ?: return MissionTaskParseResult.Reject(
                "invalid_tasks",
                "tasks[$index] must be an object with a `prompt`.",
            )
        val prompt = (obj["prompt"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (prompt.isNullOrEmpty()) {
            return MissionTaskParseResult.Reject(
                "invalid_tasks",
                "tasks[$index].prompt is required and must not be blank.",
            )
        }
        val title = (obj["title"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val agent = (obj["agent"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        tasks += MissionTask(prompt = prompt, title = title, agent = agent)
    }
    return MissionTaskParseResult.Ok(tasks)
}

/** Default 3; anything outside 1..6 is clamped, never honoured verbatim. */
internal fun clampMissionConcurrency(raw: Int?): Int =
    (raw ?: MissionLimits.DEFAULT_MAX_CONCURRENCY).coerceIn(1, MissionLimits.MAX_MAX_CONCURRENCY)

/**
 * The run label: the caller's title, else the prompt's first non-blank line. Always non-blank and
 * never longer than [SubAgentDefaults.MAX_LABEL_LENGTH], because the request validator rejects
 * anything longer — a long prompt must not fail a dispatch over a display string.
 */
internal fun missionLabel(task: MissionTask, index: Int): String {
    val title = task.title.orEmpty()
    if (title.isNotEmpty()) return title.take(SubAgentDefaults.MAX_LABEL_LENGTH)
    val firstLine = task.prompt.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    return firstLine.take(SubAgentDefaults.MAX_LABEL_LENGTH).ifEmpty { "mission-${index + 1}" }
}

/** Caps one child's result so six children cannot flood the parent's context. */
internal fun missionExcerpt(text: String?): String? {
    if (text == null || text.length <= MissionLimits.RESULT_EXCERPT_CHARS) return text
    return text.take(MissionLimits.RESULT_EXCERPT_CHARS) +
        "\n… truncated at ${MissionLimits.RESULT_EXCERPT_CHARS} characters; " +
        "read the full result with subagent_get."
}

/** One ended child, ready to serialise. */
internal data class MissionOutcome(
    val index: Int,
    val label: String,
    val status: String,
    val runId: String? = null,
    val agent: String? = null,
    val result: String? = null,
    val truncated: Boolean = false,
    val error: String? = null,
    val detail: String? = null,
    val tokensIn: Long = 0,
    val tokensOut: Long = 0,
)

internal fun encodeMissionOutcomes(outcomes: List<MissionOutcome>, maxConcurrency: Int): String =
    buildJsonObject {
        val succeeded = outcomes.count { it.status == SubAgentStatus.SUCCEEDED.name }
        put("dispatched", outcomes.size)
        put("succeeded", succeeded)
        put("failed", outcomes.size - succeeded)
        put("max_concurrency", maxConcurrency)
        put(
            "results",
            buildJsonArray {
                outcomes.forEach { outcome ->
                    addJsonObject {
                        put("index", outcome.index)
                        put("title", outcome.label)
                        put("status", outcome.status)
                        if (outcome.runId != null) put("id", outcome.runId)
                        if (outcome.agent != null) put("agent", outcome.agent)
                        if (outcome.result != null) put("result", outcome.result)
                        if (outcome.truncated) put("truncated", true)
                        if (outcome.error != null) put("error", outcome.error)
                        if (outcome.detail != null) put("detail", outcome.detail)
                        put("tokens_in", outcome.tokensIn)
                        put("tokens_out", outcome.tokensOut)
                    }
                }
            },
        )
    }.toString()

/**
 * The batch dispatch tool. Registered under the same `SubAgents` toggle and the same
 * `SubAgentDispatchProfile` gate as `subagent_dispatch` — a `NEVER` profile registers neither.
 */
fun missionDispatchTool(
    engine: SubAgentEngine,
    callerContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
    definitions: List<AgentDefinition> = emptyList(),
): Tool {
    val enabledDefinitions = AgentDefinitionResolver.enabledDefinitions(definitions)
    val description = buildString {
        append(
            """
                Dispatch several focused sub-agents in ONE call — each gets a clean context, runs
                in parallel, and returns a short result. Use it when a job decomposes into
                independent parts (compare N sources, check N files, draft N variants) that would
                otherwise be serialised through subagent_dispatch, or when the user asks for
                parallel work.

                Pass ${MissionLimits.MIN_TASKS}-${MissionLimits.MAX_TASKS} tasks; more than
                ${MissionLimits.MAX_TASKS} is rejected, not trimmed. Each entry is
                { prompt, title?, agent? }: `prompt` is the self-contained task (the child never
                sees this conversation — restate what it needs), `title` is the short label the
                user sees in the run list, `agent` names a configured expert.

                max_concurrency (1-${MissionLimits.MAX_MAX_CONCURRENCY}, default
                ${MissionLimits.DEFAULT_MAX_CONCURRENCY}) is how many of them run at once; it can
                only narrow this call, never raise the assistant's own cap. The call blocks until
                EVERY child is terminal, so keep each task small — a batch of long tasks is a
                long wait. Each result is truncated to
                ${MissionLimits.RESULT_EXCERPT_CHARS} characters and carries its run id:
                subagent_get reads the full text when an excerpt is not enough.

                Approval-required: one confirmation covers the whole batch, so the user sees every
                task before any of them runs.
            """.trimIndent()
        )
        if (enabledDefinitions.isNotEmpty()) {
            appendLine()
            appendLine()
            append("Named experts (pass the name as `agent` in a task):\n")
            append(enabledDefinitions.joinToString("\n") { "- ${it.name}: ${it.description}" })
        }
    }
    return Tool(
        name = "mission_dispatch",
        description = description,
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("tasks", buildJsonObject {
                        put("type", "array")
                        put(
                            "description",
                            "${MissionLimits.MIN_TASKS}-${MissionLimits.MAX_TASKS} independent tasks, " +
                                "each dispatched as its own sub-agent run.",
                        )
                        put("items", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("prompt", buildJsonObject {
                                    put("type", "string")
                                    put(
                                        "description",
                                        "The self-contained task for this child. It does not see " +
                                            "this conversation, so restate any context it needs.",
                                    )
                                })
                                put("title", buildJsonObject {
                                    put("type", "string")
                                    put(
                                        "description",
                                        "Short label for the run list. Omit to use the prompt's " +
                                            "first line.",
                                    )
                                })
                                if (enabledDefinitions.isNotEmpty()) {
                                    put("agent", buildJsonObject {
                                        put("type", "string")
                                        put(
                                            "description",
                                            "Name of a configured expert (case-insensitive), " +
                                                "supplying that expert's model and system prompt. " +
                                                "Unknown names fail that task and list the valid " +
                                                "names.",
                                        )
                                    })
                                }
                            })
                            put("required", buildJsonArray { add("prompt") })
                        })
                    })
                    put("max_concurrency", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "How many tasks run at once, " +
                                "${MissionLimits.MIN_TASKS}-${MissionLimits.MAX_MAX_CONCURRENCY}, " +
                                "default ${MissionLimits.DEFAULT_MAX_CONCURRENCY}. Values outside " +
                                "the range are clamped.",
                        )
                    })
                },
                required = listOf("tasks"),
            )
        },
        needsApproval = { true },
        execute = { args ->
            // Same Fork Bomb guard as subagent_dispatch, and for the same reason: a headless
            // caller (cron / workflow / another sub-agent) has no conversation to answer to, so
            // fanning out from there multiplies runs nobody is watching.
            if (callerContext.isHeadless) {
                return@Tool errEnv(
                    "no_recursion",
                    "mission_dispatch is not allowed from inside a headless run (cron / workflow / " +
                        "sub-agent / external automation). Do the work inline instead.",
                )
            }
            val params = args.jsonObject
            val tasks = when (val parsed = parseMissionTasks(params["tasks"])) {
                is MissionTaskParseResult.Reject -> return@Tool errEnv(parsed.error, parsed.detail)
                is MissionTaskParseResult.Ok -> parsed.tasks
            }
            val maxConcurrency = clampMissionConcurrency(
                params["max_concurrency"]?.jsonPrimitive?.intOrNull,
            )
            // Legacy registration paths pass no context; an empty assistant id is the engine's
            // "unknown caller" sentinel and a null chat id means "not headless", which is the
            // pre-context behaviour those paths expect.
            val parentAssistantId = callerContext.callerAssistantId.orEmpty()
            val parentChatId: String? = callerContext.callerConversationId
            val slots = Semaphore(maxConcurrency)
            val outcomes = withContext(Dispatchers.Default) {
                coroutineScope {
                    tasks.mapIndexed { index, task ->
                        async {
                            slots.withPermit {
                                val label = missionLabel(task, index)
                                val request = SubAgentRequest(
                                    task = task.prompt,
                                    agentName = task.agent,
                                    label = label,
                                    // Foreground: the whole point of the batch is to hand back
                                    // results, and a background dispatch returns before its run
                                    // has one. Each child still keeps its own timeout ceiling.
                                    runInBackground = false,
                                )
                                when (val res = engine.dispatch(parentAssistantId, parentChatId, request)) {
                                    is SubAgentEngine.DispatchResult.Reject -> MissionOutcome(
                                        index = index,
                                        label = label,
                                        status = "REJECTED",
                                        agent = task.agent,
                                        error = res.error,
                                        detail = res.detail,
                                    )
                                    is SubAgentEngine.DispatchResult.Ok -> {
                                        val excerpt = missionExcerpt(res.run.result)
                                        MissionOutcome(
                                            index = index,
                                            label = label,
                                            status = res.run.status.name,
                                            runId = res.run.id,
                                            agent = task.agent,
                                            result = excerpt,
                                            truncated = res.run.result != null && excerpt != res.run.result,
                                            error = res.run.error,
                                            tokensIn = res.run.tokensIn,
                                            tokensOut = res.run.tokensOut,
                                        )
                                    }
                                }
                            }
                        }
                    }.awaitAll()
                }
            }
            listOf(UIMessagePart.Text(encodeMissionOutcomes(outcomes, maxConcurrency)))
        },
    )
}
