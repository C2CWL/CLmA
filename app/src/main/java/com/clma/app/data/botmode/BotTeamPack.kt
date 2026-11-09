package com.clma.app.data.botmode

import com.clma.app.data.model.Assistant

/**
 * Minimal Markdown team-pack format (OpenMausBot-inspired, phone-side):
 *
 * ```
 * ---
 * team: 我的小队
 * ---
 * - name: 研究员
 *   system: 你负责查资料
 * - name: 写手
 *   system: 你负责润色
 * ```
 *
 * Or a plain bullet list without frontmatter. Pure parser — UI confirms before creating.
 */
data class BotTeamPackPreview(
    val teamName: String,
    val members: List<BotTeamMemberDraft>,
    val rawNotes: String = "",
)

data class BotTeamMemberDraft(
    val name: String,
    val systemPrompt: String = "",
)

private fun stripQuotes(value: String): String {
    val t = value.trim()
    return when {
        t.length >= 2 && t.first() == '"' && t.last() == '"' -> t.substring(1, t.length - 1)
        t.length >= 2 && t.first() == '\'' && t.last() == '\'' -> t.substring(1, t.length - 1)
        else -> t
    }
}

fun parseBotTeamPackMarkdown(raw: String): BotTeamPackPreview {
    val text = raw.replace("\r\n", "\n").trim()
    if (text.isEmpty()) {
        return BotTeamPackPreview(teamName = "", members = emptyList())
    }
    var body = text
    var teamName = ""
    if (text.startsWith("---")) {
        val end = text.indexOf("---", startIndex = 3)
        if (end > 3) {
            val fm = text.substring(3, end).trim()
            body = text.substring(end + 3).trim()
            for (line in fm.lines()) {
                val trimmed = line.trim()
                when {
                    trimmed.startsWith("team:", ignoreCase = true) ->
                        teamName = stripQuotes(trimmed.substringAfter(':'))
                    trimmed.startsWith("name:", ignoreCase = true) && teamName.isEmpty() ->
                        teamName = stripQuotes(trimmed.substringAfter(':'))
                }
            }
        }
    }
    val members = mutableListOf<BotTeamMemberDraft>()
    var currentName: String? = null
    var currentSystem = StringBuilder()
    fun flush() {
        val n = currentName?.trim().orEmpty()
        if (n.isNotEmpty()) {
            members += BotTeamMemberDraft(name = n, systemPrompt = currentSystem.toString().trim())
        }
        currentName = null
        currentSystem = StringBuilder()
    }
    val bulletNameRe = Regex("""^-\s*name\s*:\s*(.+)$""", RegexOption.IGNORE_CASE)
    val bulletOnlyRe = Regex("""^-\s+(.+)$""")
    for (line in body.lines()) {
        val trimmed = line.trim()
        val bulletName = bulletNameRe.matchEntire(trimmed)
        val bulletOnly = bulletOnlyRe.matchEntire(trimmed)
        when {
            bulletName != null -> {
                flush()
                currentName = stripQuotes(bulletName.groupValues[1])
            }
            trimmed.startsWith("system:", ignoreCase = true) ||
                trimmed.startsWith("prompt:", ignoreCase = true) -> {
                if (currentName == null) currentName = "Bot"
                val value = stripQuotes(trimmed.substringAfter(':'))
                if (currentSystem.isNotEmpty()) currentSystem.append('\n')
                currentSystem.append(value)
            }
            trimmed.startsWith("name:", ignoreCase = true) -> {
                if (currentName != null && currentSystem.isNotEmpty()) flush()
                currentName = stripQuotes(trimmed.substringAfter(':'))
            }
            bulletOnly != null && !trimmed.contains(':') -> {
                flush()
                currentName = stripQuotes(bulletOnly.groupValues[1])
            }
            currentName != null && trimmed.isNotEmpty() && !trimmed.startsWith('#') -> {
                if (currentSystem.isNotEmpty()) currentSystem.append('\n')
                currentSystem.append(trimmed)
            }
        }
    }
    flush()
    if (teamName.isEmpty() && members.isNotEmpty()) {
        teamName = members.first().name + " 小队"
    }
    return BotTeamPackPreview(
        teamName = teamName,
        members = members,
        rawNotes = body.lines().filter { it.trim().startsWith('#') }.joinToString("\n"),
    )
}

fun BotTeamMemberDraft.toAssistant(): Assistant =
    Assistant(
        name = name.trim().ifBlank { "Bot" },
        systemPrompt = systemPrompt,
    )
