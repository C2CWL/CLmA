package com.clma.app.data.ai

/**
 * Single place for assembling the system prompt that is sent to every provider.
 *
 * Callers provide pre-rendered sections so the ordering and formatting live in one spot
 * rather than being reimplemented in GenerationHandler and the provider adapters.
 *
 * Ordering is **stable-first**: the assistant prompt and tool prompts (byte-identical turn
 * to turn) come first, then the volatile sections (memory, recent chats, per-call
 * addendum) that change between turns. This lets prompt caching work: auto-caching
 * providers (OpenAI/DeepSeek/Grok/Gemini) reuse the stable byte-prefix, and OpenRouter's
 * explicit cache_control breakpoint is placed at the stable/volatile boundary (see
 * ChatCompletionsAPI). Volatile text in the prefix busts the cache every turn, which is
 * what happened before when memory was enabled.
 *
 * Precedence: when the assistant prompt is non-blank it is wrapped in
 * `<user_instructions priority="highest">` and headed by a short notice so later sections
 * (tools, memory, recent chats, addendum, policy) yield to it on conflict. Tool prompts
 * are left unmarked so call-format constraints stay intact; memory/context are still
 * followed normally and only lose on explicit conflict.
 */
class SystemPromptBuilder {

    /**
     * Returns the system prompt split into `(stable, volatile)`.
     * - stable: assistant prompt + tool cost guidance + tool prompts.
     * - volatile: memory + recent chats + per-call addendum.
     * Either may be blank.
     */
    fun buildSections(
        assistantPrompt: String,
        memoryPrompt: String = "",
        recentChatsPrompt: String = "",
        toolPrompts: List<String> = emptyList(),
        systemAddendum: String? = null,
        policyPrompt: String = "",
    ): Pair<String, String> {
        val stable = buildString {
            if (assistantPrompt.isNotBlank()) {
                appendLine(PRECEDENCE_NOTICE)
                appendLine("<user_instructions priority=\"highest\">")
                appendLine(assistantPrompt)
                append("</user_instructions>")
            }
            if (toolPrompts.isNotEmpty()) {
                if (isNotEmpty()) appendLine()
                appendLine("Tool cost guidance: prefer low-cost text tools before expensive visual or broad tools. Use read_window_tree/browser_get_text before screenshots when text is enough, and avoid repeating high-cost tools unless the state likely changed.")
                toolPrompts.forEachIndexed { index, toolPrompt ->
                    if (index > 0) appendLine()
                    append(toolPrompt)
                }
            }
        }.trim()

        val volatile = buildString {
            val hasReference =
                memoryPrompt.isNotBlank() ||
                    recentChatsPrompt.isNotBlank() ||
                    !systemAddendum.isNullOrBlank() ||
                    policyPrompt.isNotBlank()
            if (hasReference) {
                appendLine(REFERENCE_NOTICE)
            }
            if (memoryPrompt.isNotBlank()) append(memoryPrompt)
            if (recentChatsPrompt.isNotBlank()) {
                if (isNotEmpty()) appendLine()
                append(recentChatsPrompt)
            }
            if (!systemAddendum.isNullOrBlank()) {
                if (isNotEmpty()) appendLine()
                append(systemAddendum)
            }
            if (policyPrompt.isNotBlank()) {
                if (isNotEmpty()) appendLine()
                append(policyPrompt)
            }
        }.trim()

        return stable to volatile
    }

    /** Combined single-string prompt (stable then volatile), for callers/providers that do
     *  not split into cache blocks. */
    fun build(
        assistantPrompt: String,
        memoryPrompt: String = "",
        recentChatsPrompt: String = "",
        toolPrompts: List<String> = emptyList(),
        systemAddendum: String? = null,
        policyPrompt: String = "",
    ): String {
        val (stable, volatile) = buildSections(
            assistantPrompt, memoryPrompt, recentChatsPrompt, toolPrompts, systemAddendum, policyPrompt
        )
        return listOf(stable, volatile).filter { it.isNotBlank() }.joinToString("\n")
    }

    companion object {
        const val PRECEDENCE_NOTICE =
            "【优先级】<user_instructions> 内的内容优先级最高。下文的工具说明、记忆、最近对话、运行时补充均为辅助信息；与其冲突时一律以 <user_instructions> 为准。"

        const val REFERENCE_NOTICE =
            "以下为记忆与上下文，请正常遵循其中的偏好和规则；仅当与 <user_instructions> 冲突时，以后者为准。"
    }
}
