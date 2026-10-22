package me.rerere.rikkahub.workflow.execution

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * T-07 — dynamic arguments for workflow actions.
 *
 * A workflow action is `{ tool, args }`; without this file every action is a closed box, so a
 * workflow can fetch an API but can never *use* what it fetched. This object adds a tiny,
 * deliberately non-Turing-complete substitution language so action N's output (and a stored
 * secret) can be fed into action N+1's arguments:
 *
 *   {{actions[0].text}}                  whole captured text of action 0
 *   {{actions[0].json}}                  same, asserted to be JSON
 *   {{actions[0].json.data.items[2].id}} JSON path into action 0's output
 *   {{secret:WEATHER_API_KEY}}           a secret from WorkflowSecretsStore
 *
 * Safety properties (each one is covered by a test):
 *  - Substitution is **single pass**. An injected value is never re-scanned for references, so
 *    hostile response content cannot smuggle a second expansion.
 *  - Only **string** values are templated; a template can never turn into a JSON number, an
 *    object, or a new key. There is no way to inject structure.
 *  - Only **backward** references are allowed (`actions[N]` with N < the current action), and
 *    the whole plan is validated before any substitution happens, so a mid-sequence failure
 *    never leaves a half-filled argument set.
 *  - Secret references are only accepted in `web_fetch`'s `headers.*` values. A secret can
 *    therefore never be substituted into a shell argument or into the run-history summary.
 *  - Every failure is a NAMED error, never a silently-empty string: a workflow that cannot
 *    resolve a reference fails loudly with the action index and the offending reference.
 *
 * The caller (WorkflowActionRunner) runs the HARDLINE guard on the **resolved** arguments,
 * after substitution — otherwise a template could be used to assemble a command that the
 * literal-argument check never saw.
 */
object ActionTemplates {

    /** Hard ceiling on references inside one action's args. */
    const val MAX_REFS_PER_ACTION = 16

    /** Hard ceiling on the length of one substituted value. */
    const val MAX_VALUE_CHARS = 4096

    /** How much of an action's textual output is retained for later reference. */
    const val MAX_CAPTURED_OUTPUT_CHARS = 8192

    /** Hard ceiling on the segment count of a JSON path. */
    const val MAX_PATH_DEPTH = 16

    /** Only this tool may carry a `{{secret:...}}` reference, and only in its headers. */
    const val SECRET_CAPABLE_TOOL = "web_fetch"

    private const val ACTIONS_PREFIX = "actions["
    private const val SECRET_MARKER = "secret:"

    /** `{{ ... }}`, inner text 1..200 chars, no nesting (a `{`/`}` can never appear inside). */
    private val REF_PATTERN = Regex("""\{\{([^{}]{1,200})\}\}""")

    /** Secret names are lowercase-safe identifiers so they survive a prefs round-trip. */
    private val SECRET_NAME_PATTERN = Regex("""[A-Za-z0-9_]{1,64}""")

    /** A parsed reference. */
    sealed interface Ref {
        val raw: String

        /** `{{actions[N].text}}` — [jsonPath] null. `{{actions[N].json.a.b}}` — non-null path. */
        data class ActionRef(
            override val raw: String,
            val index: Int,
            val jsonPath: List<String>?,
        ) : Ref

        /** `{{secret:NAME}}` — resolved against [WorkflowSecretsStore]. */
        data class SecretRef(override val raw: String, val name: String) : Ref
    }

    /** Result of one [resolve] call. */
    sealed interface Outcome {
        /** [args] is the rebuilt argument object; [usedRefs] counts substituted references. */
        data class Ok(val args: JsonObject, val usedRefs: Int) : Outcome

        /** Named failure — the caller must abort the action and surface this verbatim. */
        data class Err(val code: String, val detail: String) : Outcome
    }

    private sealed interface RefOutcome {
        data class Ok(val ref: Ref) : RefOutcome
        data class Err(val code: String, val detail: String) : RefOutcome
    }

    /**
     * Replace every template reference in [args] with its value.
     *
     * @param actionIndex index of the action being prepared; references must point strictly
     *        below it.
     * @param toolName the action's tool — drives the secret-location policy.
     * @param outputs captured text of actions `0 until actionIndex`, in order.
     * @param secrets resolved values for the referenced secret names (see
     *        [referencedSecretNames]).
     * @param knownSecretNames every name the store currently holds, so a typo is reported as
     *        `secret_not_found` rather than silently becoming an empty header.
     *
     * Returns [Outcome.Ok] with the **same instance** when [args] holds no reference, which
     * keeps the templating-disabled and templating-enabled-but-unused paths identical.
     */
    fun resolve(
        args: JsonObject,
        actionIndex: Int,
        toolName: String,
        outputs: List<String>,
        secrets: Map<String, String>,
        knownSecretNames: Set<String>,
    ): Outcome {
        val found = collect(args)
        if (found.isEmpty()) return Outcome.Ok(args, 0)
        if (found.size > MAX_REFS_PER_ACTION) {
            return Outcome.Err(
                "too_many_refs",
                "action $actionIndex has ${found.size} template references; the limit is $MAX_REFS_PER_ACTION",
            )
        }

        val resolved = LinkedHashMap<String, String>()
        for (site in found) {
            if (resolved.containsKey(site.inner)) continue
            val ref = when (val r = parseRef(site.inner, actionIndex, toolName, site.path)) {
                is RefOutcome.Err -> return Outcome.Err(r.code, r.detail)
                is RefOutcome.Ok -> r.ref
            }
            val value = when (ref) {
                is Ref.ActionRef -> {
                    val missed = if (ref.jsonPath == null) {
                        "action ${ref.index} produced no text"
                    } else {
                        "no such JSON path in action ${ref.index}'s output"
                    }
                    resolveActionRef(ref, actionIndex, outputs) ?: return Outcome.Err(
                        if (ref.jsonPath == null) "unknown_action_output" else "json_path_not_found",
                        "action $actionIndex: cannot read '${ref.raw}' - $missed",
                    )
                }
                is Ref.SecretRef -> when {
                    ref.name !in knownSecretNames -> return Outcome.Err(
                        "secret_not_found",
                        "action $actionIndex: no stored secret named '${ref.name}'",
                    )
                    else -> secrets[ref.name] ?: return Outcome.Err(
                        "secret_not_found",
                        "action $actionIndex: no stored secret named '${ref.name}'",
                    )
                }
            }
            if (value.length > MAX_VALUE_CHARS) {
                return Outcome.Err(
                    "value_too_large",
                    "action $actionIndex: '${ref.raw}' resolved to ${value.length} chars; the limit is $MAX_VALUE_CHARS",
                )
            }
            resolved[site.inner] = value
        }

        return Outcome.Ok(rewriteObject(args, resolved), found.size)
    }

    /**
     * Secret names referenced by [args], so the caller can load exactly those values (and only
     * those) from the store before calling [apply]. References that are malformed or out of
     * place are ignored here — [resolve] reports them with full context.
     */
    fun referencedSecretNames(args: JsonObject): Set<String> {
        val names = LinkedHashSet<String>()
        for (site in collect(args)) {
            if (!site.inner.startsWith(SECRET_MARKER)) continue
            val name = site.inner.removePrefix(SECRET_MARKER).trim()
            if (SECRET_NAME_PATTERN.matches(name)) names += name
        }
        return names
    }

    /**
     * True when a `{{secret:...}}` reference is allowed to sit at [path] inside a call to
     * [toolName]. Deliberately narrow: only `web_fetch`'s request headers. Anything wider
     * (shell args, file contents, notification bodies) would let a secret leak into a place
     * the user never reviewed.
     */
    fun secretRefAllowed(toolName: String, path: List<String>): Boolean =
        toolName == SECRET_CAPABLE_TOOL && path.size == 2 && path[0] == "headers"

    // -- internals ----------------------------------------------------------

    private data class Site(val path: List<String>, val inner: String)

    private fun collect(args: JsonObject): List<Site> {
        val out = mutableListOf<Site>()
        fun walk(element: JsonElement, path: List<String>) {
            when (element) {
                is JsonObject -> for ((key, value) in element) walk(value, path + key)
                is JsonArray -> for ((index, value) in element.withIndex()) walk(value, path + index.toString())
                is JsonPrimitive -> {
                    if (!element.isString) return
                    for (match in REF_PATTERN.findAll(element.content)) {
                        out += Site(path, match.groupValues[1].trim())
                    }
                }
            }
        }
        walk(args, emptyList())
        return out
    }

    private fun parseRef(
        inner: String,
        actionIndex: Int,
        toolName: String,
        path: List<String>,
    ): RefOutcome {
        if (inner.startsWith(SECRET_MARKER)) {
            val name = inner.removePrefix(SECRET_MARKER).trim()
            if (!SECRET_NAME_PATTERN.matches(name)) {
                return RefOutcome.Err(
                    "bad_secret_name",
                    "secret names are 1..64 chars of [A-Za-z0-9_], got '$name'",
                )
            }
            if (!secretRefAllowed(toolName, path)) {
                return RefOutcome.Err(
                    "secret_not_allowed",
                    "{{secret:$name}} may only appear in ${SECRET_CAPABLE_TOOL} request headers, not in '${path.joinToString(".")}'",
                )
            }
            return RefOutcome.Ok(Ref.SecretRef(inner, name))
        }

        if (!inner.startsWith(ACTIONS_PREFIX)) {
            return RefOutcome.Err(
                "unknown_reference",
                "'$inner' is not a reference; expected actions[N].text, actions[N].json[.path] or secret:NAME",
            )
        }
        val close = inner.indexOf(']')
        if (close < 0) {
            return RefOutcome.Err("bad_action_reference", "'$inner' is missing the closing ']'")
        }
        val index = inner.substring(ACTIONS_PREFIX.length, close).trim().toIntOrNull()
            ?: return RefOutcome.Err("bad_action_index", "'$inner' has a non-numeric action index")
        if (index < 0 || index >= actionIndex) {
            return RefOutcome.Err(
                "forward_reference",
                "action $actionIndex may only read earlier actions (0..${actionIndex - 1}), got $index",
            )
        }
        val rest = inner.substring(close + 1)
        when {
            rest == ".text" -> return RefOutcome.Ok(Ref.ActionRef(inner, index, null))
            rest == ".json" -> return RefOutcome.Ok(Ref.ActionRef(inner, index, emptyList()))
            rest.startsWith(".json") -> {
                val jsonPath = parsePath(rest.removePrefix(".json"))
                    ?: return RefOutcome.Err("bad_json_path", "'$inner' has an unparseable JSON path")
                if (jsonPath.size > MAX_PATH_DEPTH) {
                    return RefOutcome.Err(
                        "json_path_too_deep",
                        "'$inner' nests ${jsonPath.size} path segments; the limit is $MAX_PATH_DEPTH",
                    )
                }
                return RefOutcome.Ok(Ref.ActionRef(inner, index, jsonPath))
            }
            else -> return RefOutcome.Err(
                "bad_action_field",
                "'$inner' must end in .text or .json[.path]",
            )
        }
    }

    /** `""` → empty path; `.a.b` / `[2]` / `.a[2].b` → segments. null when malformed. */
    private fun parsePath(rest: String): List<String>? {
        if (rest.isEmpty()) return emptyList()
        val segments = mutableListOf<String>()
        var i = 0
        while (i < rest.length) {
            when (rest[i]) {
                '.' -> {
                    i++
                    val start = i
                    while (i < rest.length && rest[i] != '.' && rest[i] != '[') i++
                    if (i == start) return null
                    segments += rest.substring(start, i)
                }
                '[' -> {
                    val close = rest.indexOf(']', i)
                    if (close < 0) return null
                    val raw = rest.substring(i + 1, close)
                    if (raw.isEmpty() || raw.any { !it.isDigit() }) return null
                    segments += raw
                    i = close + 1
                }
                else -> return null
            }
        }
        return segments
    }

    private fun resolveActionRef(
        ref: Ref.ActionRef,
        actionIndex: Int,
        outputs: List<String>,
    ): String? {
        if (ref.index >= actionIndex) return null
        val text = outputs.getOrNull(ref.index) ?: return null
        val jsonPath = ref.jsonPath ?: return text
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return null
        var cursor: JsonElement = root
        for (segment in jsonPath) {
            cursor = when (val current = cursor) {
                is JsonObject -> current[segment] ?: return null
                is JsonArray -> segment.toIntOrNull()?.let { current.getOrNull(it) } ?: return null
                else -> return null
            }
        }
        return when (cursor) {
            is JsonNull -> "null"
            is JsonPrimitive -> cursor.contentOrNull
            else -> cursor.toString()
        }
    }

    /** Typed entry point for the top-level object, so the result stays a [JsonObject]. */
    private fun rewriteObject(obj: JsonObject, resolved: Map<String, String>): JsonObject =
        JsonObject(obj.mapValues { (_, value) -> rewrite(value, resolved) })

    /** Single-pass rebuild: values captured from [resolved] are inserted verbatim, never re-read. */
    private fun rewrite(element: JsonElement, resolved: Map<String, String>): JsonElement =
        when (element) {
            is JsonObject -> JsonObject(element.mapValues { (_, value) -> rewrite(value, resolved) })
            is JsonArray -> JsonArray(element.map { rewrite(it, resolved) })
            is JsonPrimitive -> {
                if (!element.isString) {
                    element
                } else {
                    val original = element.content
                    JsonPrimitive(REF_PATTERN.replace(original) { match ->
                        resolved[match.groupValues[1].trim()] ?: match.value
                    })
                }
            }
        }
}
