package com.clma.app.data.ai.prompts

internal val DEFAULT_COMPRESS_PROMPT = """
    You are a conversation compression assistant. Compress the conversation below into a dense, state-preserving summary (State Sheet) that lets another assistant seamlessly continue without the original messages.

    Rules:
    1. Target approximately {target_tokens} tokens. Output is cut off at the limit, so keep the section order strictly and never pad.
    2. Write in {locale}; keep code, commands, identifiers, paths, URLs, and quotes exactly as-is.
    3. Output ONLY the summary. First line MUST be: [Summary of previous conversation] (or its {locale} equivalent).
    4. Terse, high-density facts, one bullet per point. Copy exact values verbatim (paths, URLs, commit SHAs, IDs, numbers, commands, errors).
    5. Maintain a dedicated State Sheet: track active phase, environment variables, modified files, and component states explicitly.
    6. Omit empty sections. Drop pleasantries, chit-chat, and superseded ideas; record only the latest authoritative state.
    7. Input may contain earlier summaries: merge into a single unified summary, consolidating sections without duplicate headers.
    8. Preserve completed tool calls and outcomes: tool name, key arguments, factual results or errors, and critical paths/IDs/state changes; never vaguely claim tools were run.

    ## Goal & Current Phase — what the user wants, current milestone, active status
    ## Active State Sheet — key variables, environments, file states, entities, IDs & flags
    ## Progress & Open Items — completed items [x], unfinished work [ ], blockers, next immediate step
    ## Decisions, Constraints & Rules — authoritative choices, forbidden actions, limits
    ## Key Code, Files & Architecture — exact paths, symbols, commit SHAs, schema
    ## Tool Execution History & Results — tool(args) → factual result/error (never omit key outputs)
    ## User Preferences & Quirks — explicit preferences, habits, instructions

    {additional_context}

    <conversation>
    {content}
    </conversation>
""".trimIndent()

/**
 * Structured prompt default v2 (before State Sheet specification).
 * Kept verbatim so users who never customized the prompt are automatically upgraded.
 */
internal val LEGACY_DEFAULT_COMPRESS_PROMPT_V2 = """
    You are a conversation compression assistant. Compress the conversation below into a dense, structured summary that lets another assistant continue without the original messages.

    Rules:
    1. About {target_tokens} tokens; output is cut off at the limit, so keep the section order and never pad.
    2. Write in {locale}; keep code, commands, quotes and identifiers as-is.
    3. Output only the summary. First line: [Summary of previous conversation] (or its {locale} equivalent).
    4. Terse bullets, one fact each, no repetition. Copy exact values verbatim (paths, URLs, IDs, numbers, commands, errors).
    5. Omit empty sections. Drop small talk and superseded ideas; keep only the latest state.
    6. Input may be one segment or contain earlier summaries: merge into one header, each section once, newer replacing older.
    7. Preserve completed tool calls: tool name, key arguments, factual result or error, and important paths/IDs/state changes; never just say tools were used.

    ## Goal — what the user wants; current task and status
    ## Open items — unfinished work, pending questions, next step
    ## Decisions & constraints
    ## Key facts, files & code
    ## Tool execution history — tool(args) → result/error
    ## User preferences

    {additional_context}

    <conversation>
    {content}
    </conversation>
""".trimIndent()

/**
 * Previous built-in default v1 (before the structured prompt). Kept verbatim so that users who
 * never customized the compress prompt are upgraded automatically.
 */
internal val LEGACY_DEFAULT_COMPRESS_PROMPT_V1 = """
    You are a conversation compression assistant. Compress the following conversation into a concise summary.

    Requirements:
    1. Preserve key facts, decisions, and important context that would be needed to continue the conversation
    2. Keep the summary in the same language as the original conversation
    3. Target approximately {target_tokens} tokens
    4. Output the summary directly without any explanations or meta-commentary
    5. Format the summary as context information that can be used to continue the conversation
    6. Use {locale} language
    7. Start the output with a clear indicator that this is a summary (e.g., "[Summary of previous conversation]" or equivalent in the target language)
    8. Preserve completed tool calls and their outcomes. Include tool names, meaningful arguments,
       factual results or errors, and important file paths, URLs, IDs, and state changes. Do not
       replace them with a vague statement that tools were used.

    {additional_context}

    <conversation>
    {content}
    </conversation>
""".trimIndent()

private fun normalizeCompressPrompt(text: String): String =
    text.replace("\r\n", "\n").replace('\r', '\n').trim()

/**
 * Returns the compress prompt to use for a stored preference value.
 * A missing value or an untouched legacy default (V1 or V2) maps to [DEFAULT_COMPRESS_PROMPT];
 * any user-customized prompt is returned unchanged.
 */
internal fun upgradeLegacyCompressPrompt(stored: String?): String {
    if (stored == null) return DEFAULT_COMPRESS_PROMPT
    val normalized = normalizeCompressPrompt(stored)
    return if (
        normalized == normalizeCompressPrompt(LEGACY_DEFAULT_COMPRESS_PROMPT_V1) ||
        normalized == normalizeCompressPrompt(LEGACY_DEFAULT_COMPRESS_PROMPT_V2)
    ) {
        DEFAULT_COMPRESS_PROMPT
    } else {
        stored
    }
}
