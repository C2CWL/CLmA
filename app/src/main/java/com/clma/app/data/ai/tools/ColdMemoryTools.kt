package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/**
 * T-06 / (7) — Cold memory: a plain Markdown knowledge base on disk, read on demand.
 *
 * The built-in `memory_tool` (hot memory) keeps a handful of short, always-injected records in
 * the app database. That does not scale: a hundred-page project journal would be re-sent to the
 * model on every single request. Cold memory flips the model around — the documents stay on
 * disk, and the model pulls in only the ones it needs, when it needs them:
 *
 *  - `memory_index` lists what is in the directory (plus `INDEX.md`, the human-written table of
 *    contents, if present);
 *  - `memory_read` returns one document, windowed so a 40-page file cannot blow up the context;
 *  - `memory_write` appends to (or overwrites) one document — gated behind user approval.
 *
 * Nothing here touches Android, the app database or the workspace API: every IO access is an
 * injected lambda, so the tool contract and the file-name resolution rules are unit-testable.
 * The caller (`ChatService`) supplies the lambdas, which route to `WorkspaceRepository`.
 *
 * This file owns no storage layout of its own: [ColdMemoryRules.normalizeDir] is the single
 * place that knows how a user-picked workspace path turns into the relative directory the
 * repository methods expect.
 */
data class ColdMemoryDoc(
    val name: String,
    val sizeBytes: Long,
)

/** Result of a `memory_write` call, already persisted by the caller's lambda. */
data class ColdMemoryWriteResult(
    val fileName: String,
    val mode: String,
    val totalChars: Int,
)

/** Outcome of resolving a model-supplied `file` argument against the directory listing. */
sealed interface ColdMemoryLookup {
    data class Found(val name: String) : ColdMemoryLookup
    data class Ambiguous(val candidates: List<String>) : ColdMemoryLookup
    object NotFound : ColdMemoryLookup
}

/** A single window of a document, so the model can continue reading past the limit. */
data class ColdMemoryWindow(
    val content: String,
    val start: Int,
    val endExclusive: Int,
    val totalChars: Int,
    val truncated: Boolean,
)

/**
 * Pure helpers for the cold-memory directory. No IO, no Android — everything here is exercised
 * directly by `ColdMemoryToolsTest`.
 */
object ColdMemoryRules {

    /** The workspace prefix the picker UI speaks; storage methods take the relative remainder. */
    const val WORKSPACE_PREFIX = "/workspace"

    const val MARKDOWN_SUFFIX = ".md"

    /** The conventional table-of-contents file. Optional, but listed first when present. */
    const val INDEX_FILE_NAME = "INDEX.md"

    /** How many characters one `memory_read` call returns by default. */
    const val READ_WINDOW_CHARS = 16_000

    /** Cap on the `INDEX.md` body echoed by `memory_index`. */
    const val INDEX_MAX_CHARS = 16_000

    private const val MAX_CANDIDATES = 20

    fun isMarkdown(name: String): Boolean = name.endsWith(MARKDOWN_SUFFIX, ignoreCase = true)

    /**
     * Turns what the workspace directory picker returns (`/workspace/notes/memory`) into the
     * relative path the repository methods want (`notes/memory`). Returns `null` when the input
     * is blank or tries to escape the workspace (any `..` segment) — the caller then registers
     * no tool at all. The empty string is a legitimate result and means "the workspace root".
     */
    fun normalizeDir(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val relative = trimmed
            .removePrefix("$WORKSPACE_PREFIX/")
            .removePrefix(WORKSPACE_PREFIX)
        var cleaned = relative.replace('\\', '/')
        while (cleaned.startsWith("./")) cleaned = cleaned.removePrefix("./")
        cleaned = cleaned.trim('/')
        if (cleaned.isEmpty()) return ""
        if (cleaned.split('/').any { it == ".." }) return null
        return cleaned
    }

    /**
     * File names `memory_write` will accept: a single path segment (no separators), a Markdown
     * file, and no `.` / `..` components. Deliberately strict — the write path must never be
     * able to reach outside the directory the user picked.
     */
    fun isValidWriteName(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed == "." || trimmed == "..") return false
        if (trimmed.contains('/') || trimmed.contains('\\')) return false
        if (trimmed.contains("..")) return false
        return isMarkdown(trimmed)
    }

    /**
     * Resolves a model-supplied reference. Exact file name wins, then exact name without the
     * `.md` suffix, then a unique prefix (`M01` → `M01-device-shell.md`), then a unique
     * substring. Anything matching several files is reported back as a list of candidates
     * rather than guessed at, so the model can narrow it down itself.
     */
    fun lookup(query: String, names: List<String>): ColdMemoryLookup {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return ColdMemoryLookup.NotFound
        val pool = names.sorted()

        fun uniqueOrCandidates(matches: List<String>): ColdMemoryLookup = when {
            matches.isEmpty() -> ColdMemoryLookup.NotFound
            matches.size == 1 -> ColdMemoryLookup.Found(matches[0])
            else -> ColdMemoryLookup.Ambiguous(matches.take(MAX_CANDIDATES))
        }

        val exact = pool.filter {
            val lower = it.lowercase()
            lower == q || lower.removeSuffix(MARKDOWN_SUFFIX) == q
        }
        if (exact.isNotEmpty()) return uniqueOrCandidates(exact)

        val prefix = pool.filter {
            val lower = it.lowercase()
            lower.startsWith(q) || lower.removeSuffix(MARKDOWN_SUFFIX).startsWith(q)
        }
        if (prefix.isNotEmpty()) return uniqueOrCandidates(prefix)

        return uniqueOrCandidates(pool.filter { it.lowercase().contains(q) })
    }

    /** Clips [text] to one [READ_WINDOW_CHARS] window starting at [requestedStart]. */
    fun window(text: String, requestedStart: Int?): ColdMemoryWindow {
        val total = text.length
        val start = (requestedStart ?: 0).coerceIn(0, total)
        val end = minOf(total, start + READ_WINDOW_CHARS)
        return ColdMemoryWindow(
            content = text.substring(start, end),
            start = start,
            endExclusive = end,
            totalChars = total,
            truncated = end < total,
        )
    }
}

/**
 * T-06 / (7) — builds the cold-memory tool set.
 *
 * Pure wiring: [listDocs] / [readDoc] / [writeDoc] are injected by `ChatService`, which routes
 * them at the workspace the assistant is bound to. This function never touches the filesystem
 * itself, so the whole surface can be tested with fakes.
 *
 * The factory is only called when the assistant enabled cold memory AND a workspace + directory
 * are configured. That keeps the default (flag off) tool list byte-for-byte identical to the
 * pre-T-06 one — the prompt cache never sees a new schema unless the user asked for it.
 *
 * @param dirLabel human-readable directory, echoed in the index envelope for orientation.
 * @param listDocs the Markdown files currently in the directory (INDEX.md included if present).
 * @param readDoc returns the file's text, or `null` when it does not exist.
 * @param writeDoc persists `content`, appending when `append` is true, and returns the result.
 */
fun buildColdMemoryTools(
    dirLabel: String,
    listDocs: suspend () -> List<ColdMemoryDoc>,
    readDoc: suspend (name: String) -> String?,
    writeDoc: suspend (name: String, content: String, append: Boolean) -> ColdMemoryWriteResult,
): List<Tool> = listOf(
    Tool(
        name = "memory_index",
        description = """
            List the cold-memory knowledge base: the Markdown documents available on disk, plus
            the table of contents ("${ColdMemoryRules.INDEX_FILE_NAME}") when one exists.

            Unlike the records kept by `memory_tool`, these documents are NOT in your context.
            Nothing here costs anything until you read it — start with the index, then pull in
            only the documents the current task actually needs via `memory_read`.

            Call it when the user refers to earlier work, a project journal, or notes you are
            expected to remember, and you have not read the index yet in this conversation.
        """.trimIndent(),
        parameters = { InputSchema.Obj(properties = buildJsonObject {}, required = emptyList()) },
        needsApproval = { false },
        execute = {
            val docs = listDocs()
            val markdown = docs.filter { ColdMemoryRules.isMarkdown(it.name) }
            val indexText = readDoc(ColdMemoryRules.INDEX_FILE_NAME)
            val payload = buildJsonObject {
                put("dir", dirLabel)
                put("fileCount", markdown.size)
                put("files", buildJsonArray {
                    markdown.sortedBy { it.name }.forEach { doc ->
                        add(buildJsonObject {
                            put("name", doc.name)
                            put("sizeBytes", doc.sizeBytes)
                        })
                    }
                })
                if (indexText == null) {
                    put("index", "")
                    put("note", "${ColdMemoryRules.INDEX_FILE_NAME} not found in this directory.")
                } else {
                    put("index", indexText.take(ColdMemoryRules.INDEX_MAX_CHARS))
                    if (indexText.length > ColdMemoryRules.INDEX_MAX_CHARS) {
                        put("indexTruncated", true)
                    }
                }
            }
            listOf(UIMessagePart.Text(payload.toString()))
        },
    ),
    Tool(
        name = "memory_read",
        description = """
            Read one document from the cold-memory knowledge base.

            Pass `file` as a name from `memory_index`: the full file name, or any unambiguous
            fragment of it (a number like "M03" or a word from the title). If several documents
            match, the result lists the candidates instead of guessing.

            Long documents are returned in windows of ${ColdMemoryRules.READ_WINDOW_CHARS}
            characters. When the envelope says `truncated: true`, continue with
            `memory_read(file, start = nextStart)` rather than assuming the rest is empty.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("file", buildJsonObject {
                        put("type", "string")
                        put("description", "File name or unambiguous fragment, e.g. \"M03\" or \"M03-vps-stack.md\".")
                    })
                    put("start", buildJsonObject {
                        put("type", "integer")
                        put("description", "Optional character offset to continue from. Defaults to 0.")
                    })
                },
                required = listOf("file"),
            )
        },
        needsApproval = { false },
        execute = {
            val params = it.jsonObject
            val query = params["file"]?.jsonPrimitive?.contentOrNull
                ?: return@Tool listOf(UIMessagePart.Text(errorEnvelope("missing_argument", "file is required")))
            val start = params["start"]?.jsonPrimitive?.intOrNull
            val names = listDocs().filter { ColdMemoryRules.isMarkdown(it.name) }.map { it.name }
            when (val resolved = ColdMemoryRules.lookup(query, names)) {
                is ColdMemoryLookup.Found -> {
                    val text = readDoc(resolved.name)
                    if (text == null) {
                        listOf(UIMessagePart.Text(errorEnvelope("not_found", "Could not read \"${resolved.name}\".")))
                    } else {
                        val window = ColdMemoryRules.window(text, start)
                        listOf(UIMessagePart.Text(buildJsonObject {
                            put("file", resolved.name)
                            put("start", window.start)
                            put("endExclusive", window.endExclusive)
                            put("totalChars", window.totalChars)
                            put("truncated", window.truncated)
                            if (window.truncated) {
                                put("nextStart", window.endExclusive)
                            }
                            put("content", window.content)
                        }.toString()))
                    }
                }

                is ColdMemoryLookup.Ambiguous -> listOf(UIMessagePart.Text(buildJsonObject {
                    put("error", "ambiguous")
                    put("file", query)
                    put("candidates", buildJsonArray { resolved.candidates.forEach { add(it) } })
                    put("detail", "Several documents match. Call memory_read again with one exact name.")
                }.toString()))

                ColdMemoryLookup.NotFound -> listOf(UIMessagePart.Text(buildJsonObject {
                    put("error", "not_found")
                    put("file", query)
                    put("available", buildJsonArray { names.sorted().take(50).forEach { add(it) } })
                    put("detail", "No document matches. Use memory_index to list what exists.")
                }.toString()))
            }
        },
    ),
    Tool(
        name = "memory_write",
        description = """
            Add to, or rewrite, one document in the cold-memory knowledge base.

            Use it to persist something worth keeping across conversations — a durable
            conclusion, a project note, a correction to an existing document. Prefer small,
            focused documents, and keep "${ColdMemoryRules.INDEX_FILE_NAME}" up to date when you
            add or rename a file.

            `mode` defaults to "append" (the safe choice: nothing already written is lost). Use
            "overwrite" only when you are deliberately rewriting the whole file — and read it
            first if you are not sure what it contains.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("file", buildJsonObject {
                        put("type", "string")
                        put("description", "File name, a single segment ending in .md, e.g. \"M13-new-topic.md\".")
                    })
                    put("content", buildJsonObject {
                        put("type", "string")
                        put("description", "Text to write. For append, exactly what should be added at the end.")
                    })
                    put("mode", buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                add("append")
                                add("overwrite")
                            }
                        )
                        put("description", "\"append\" (default) or \"overwrite\".")
                    })
                },
                required = listOf("file", "content"),
            )
        },
        needsApproval = { true },
        execute = {
            val params = it.jsonObject
            val name = params["file"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val content = params["content"]?.jsonPrimitive?.contentOrNull
                ?: return@Tool listOf(UIMessagePart.Text(errorEnvelope("missing_argument", "content is required")))
            if (!ColdMemoryRules.isValidWriteName(name)) {
                return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                    put("error", "invalid_name")
                    put("file", name)
                    put(
                        "detail",
                        "Use a single file name ending in ${ColdMemoryRules.MARKDOWN_SUFFIX} " +
                            "(no directory separators, no \"..\")."
                    )
                }.toString()))
            }
            val mode = params["mode"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            val append = mode != "overwrite"
            val result = writeDoc(name, content, append)
            listOf(UIMessagePart.Text(buildJsonObject {
                put("file", result.fileName)
                put("mode", result.mode)
                put("totalChars", result.totalChars)
            }.toString()))
        },
    ),
)

private fun errorEnvelope(code: String, detail: String): String = buildJsonObject {
    put("error", code)
    put("detail", detail)
}.toString()
