package me.rerere.ai.provider.stream

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/**
 * Fallback parser for models that emit tool calls as **literal text** in the message content
 * instead of as structured tool calls.
 *
 * Two shipping examples: Gemini Nano on-device ([me.rerere.ai.provider.providers.AICoreProvider])
 * and MiniMax-style models served through OpenAI-compatible gateways, where the chat template asks
 * for `<tool_call>{"name":…,"input":…}</tool_call>` and the gateway streams that block as ordinary
 * content text. Without this, the markup is shown to the user verbatim *and* the tool never runs —
 * the turn just ends as prose.
 *
 * The parser splits the content stream into plain text and complete tool-call blocks. Because a tag
 * can straddle two stream chunks it keeps an internal buffer and holds back only the few trailing
 * characters that could still be the start of an opening tag, so ordinary answers keep streaming.
 *
 * Behaviour is deliberately conservative:
 *  - only well-formed, closed blocks are considered, and only the `<tool_call>` spelling;
 *  - the payload must name a tool we were actually offered ([tools]);
 *  - anything that does not parse is passed through unchanged as text, so nothing is swallowed.
 *
 * Matching runs against a copy of the stream with the invisible characters these models wedge into
 * their markup (zero-width space / non-joiner / joiner, word-joiner, BOM) removed — see [normalize].
 */
internal class TextToolCallParser(private val tools: List<Tool> = emptyList()) {
    private val buffer = StringBuilder()
    private var inToolCall = false
    private var pendingFinishReason: String? = null

    private val openTag = "<tool_call>"
    private val closeTag = "</tool_call>"

    /**
     * Strip the invisible characters these models sprinkle into markup. `<U+200B>tool_call>` with a
     * zero-width space after the `<` is the one seen in the wild; normalising up front means a block
     * is recognised whether or not the model injected them.
     */
    private fun normalize(input: String): String {
        if (input.none { it in ZERO_WIDTH }) return input
        return buildString(input.length) {
            for (c in input) if (c !in ZERO_WIDTH) append(c)
        }
    }

    /** Feeds one content delta and returns the parts it completed (text and/or tool calls). */
    fun feed(delta: String): List<UIMessagePart> {
        if (delta.isEmpty()) return emptyList()
        buffer.append(normalize(delta))
        val out = mutableListOf<UIMessagePart>()
        while (true) {
            if (!inToolCall) {
                val openIdx = buffer.indexOf(openTag)
                if (openIdx < 0) {
                    // No complete opening tag yet. Hold back ONLY a tail that could still grow
                    // into one — everything else is flushed right away. Withholding a fixed
                    // number of trailing characters instead would re-split ordinary answers
                    // (changing the emitted chunk/part boundaries) and, worse, when the next
                    // thing to arrive is a structured tool call, the withheld tail would land
                    // *after* that tool part.
                    val safe = buffer.length - partialTagSuffixLength(buffer)
                    if (safe > 0) {
                        val text = buffer.substring(0, safe)
                        buffer.delete(0, safe)
                        if (text.isNotEmpty()) out += UIMessagePart.Text(text)
                    }
                    break
                }
                if (openIdx > 0) {
                    val pre = buffer.substring(0, openIdx)
                    if (pre.isNotEmpty()) out += UIMessagePart.Text(pre)
                }
                buffer.delete(0, openIdx + openTag.length)
                inToolCall = true
            }
            // We're inside a tool_call — wait for close tag.
            val closeIdx = buffer.indexOf(closeTag)
            if (closeIdx < 0) break
            val body = buffer.substring(0, closeIdx).trim()
            buffer.delete(0, closeIdx + closeTag.length)
            inToolCall = false
            val parsed = parseToolCallBody(body)
            if (parsed != null) {
                out += parsed
                pendingFinishReason = "tool_calls"
            } else {
                // Malformed, or names a tool we never offered — surface as plain text so the
                // model's intent is still visible instead of vanishing.
                out += UIMessagePart.Text("$openTag$body$closeTag")
            }
        }
        return out
    }

    /**
     * Length of the trailing slice of [text] that is a proper prefix of [openTag] — 0 when the
     * buffer cannot possibly be mid-tag. Only that slice ever needs to be held back between
     * chunks; a `<` that starts nothing (or a completed tag) is flushed as ordinary text.
     */
    private fun partialTagSuffixLength(text: CharSequence): Int {
        val max = (openTag.length - 1).coerceAtMost(text.length)
        for (len in max downTo 1) {
            if (text.regionMatches(text.length - len, openTag, 0, len)) return len
        }
        return 0
    }

    /** Flush whatever is left when the stream ends (e.g. an unterminated tag). */
    fun flushPending(): List<UIMessagePart> {
        if (buffer.isEmpty() && !inToolCall) return emptyList()
        // Restore the opening tag when we were still inside the block: without it the model's
        // truncated call would surface as a bare JSON blob with no hint of what it was.
        val txt = (if (inToolCall) openTag else "") + buffer.toString()
        buffer.clear()
        inToolCall = false
        return listOf(UIMessagePart.Text(txt))
    }

    /**
     * Returns `"tool_calls"` once a text-emitted tool call was recognised, so a caller can report it
     * as the finish reason; null when there was none.
     */
    fun consumePendingFinishReason(): String? {
        val r = pendingFinishReason
        pendingFinishReason = null
        return r
    }

    private fun parseToolCallBody(body: String): UIMessagePart.Tool? = try {
        val obj: JsonObject = parseLenient(body) ?: return null
        val name = (obj["name"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() } ?: return null
        // Only accept tools that were actually offered. Without this check a model could invoke
        // anything by name and we would happily dispatch it.
        if (tools.none { it.name == name }) return null
        // Coerce the arguments into a valid JSON-object string. Models sometimes emit the input as
        // a bare primitive ("input":"echo hello") instead of an object — wrap it under the tool's
        // first-required parameter so the tool's execute body finds the value where it expects.
        val rawInput = obj["input"] ?: obj["arguments"]
        val inputJson: String = when (rawInput) {
            null, is JsonNull -> "{}"
            is JsonObject -> rawInput.toString()
            is JsonPrimitive -> wrapPrimitiveInput(name, rawInput.content)
            else -> rawInput.toString()
        }
        UIMessagePart.Tool(
            toolCallId = "text-tool-${System.nanoTime()}",
            toolName = name,
            input = inputJson,
            output = emptyList(),
        )
    } catch (_: Throwable) {
        null
    }

    /**
     * The model emitted `"input": "<string>"` instead of an object. Look up the named tool's schema,
     * find its first required property, and wrap the string under that key. Falls back to "command",
     * since the most common single-argument tools take a `command` param.
     */
    private fun wrapPrimitiveInput(toolName: String, value: String): String {
        val key = inferPrimaryParamKey(toolName) ?: "command"
        return buildString {
            append("{\"")
            append(key)
            append("\":")
            append(JsonPrimitive(value))
            append("}")
        }
    }

    private fun inferPrimaryParamKey(toolName: String): String? {
        val tool = tools.firstOrNull { it.name == toolName } ?: return null
        val schema = runCatching { tool.parameters() }.getOrNull() as? InputSchema.Obj
            ?: return null
        schema.required?.firstOrNull()?.let { return it }
        return schema.properties.keys.firstOrNull()
    }

    /**
     * Parses [body] as a JSON object, repairing the malformations these models actually emit — wrong
     * closing punctuation (`}>` instead of `}}`), unbalanced braces (one `}` short), trailing commas.
     * Returns null only when no amount of repair makes the text parse, in which case the caller
     * surfaces the raw markup as text so the user sees what the model tried to emit.
     */
    private fun parseLenient(body: String): JsonObject? {
        val candidates = buildList {
            add(body)
            // `}>` → `}}` (off-by-one closing)
            add(body.replace(Regex("""\}\s*>\s*$"""), "}}"))
            add(body.replace("}>", "}}"))
            // Trailing `,}` and `,]` — strip stray commas
            add(body.replace(Regex(""",\s*\}"""), "}").replace(Regex(""",\s*\]"""), "]"))
            // Unbalanced braces — pad with `}` until balanced
            run {
                val opens = body.count { it == '{' }
                val closes = body.count { it == '}' }
                if (opens > closes) add(body + "}".repeat(opens - closes))
            }
        }
        for (variant in candidates.distinct()) {
            try {
                return Json.parseToJsonElement(variant) as? JsonObject ?: continue
            } catch (_: Throwable) {
                // try next repair
            }
        }
        return null
    }

    private companion object {
        val ZERO_WIDTH = setOf('\u200B', '\u200C', '\u200D', '\u2060', '\uFEFF')
    }
}
