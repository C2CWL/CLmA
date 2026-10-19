package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.costguards.TokenBudgetTracker
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.usage.TurnFooter
import me.rerere.rikkahub.ui.pages.stats.formatCompactTokens
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * Conversation token consumption shown in the chat top bars (regular and clean layout), summed
 * across all turns and tool calls. The usage ledger ([turnFooters]) is the ground truth (it
 * includes every tool-loop step); [TokenBudgetTracker] is the fallback for legacy or imported
 * conversations without ledger rows.
 */
internal fun conversationUsedTokens(turnFooters: Map<String, TurnFooter>, conversation: Conversation): Long =
    usedTokensOrFallback(
        ledgerTokens = turnFooters.values.mapNotNull { it.usage }.sumOf { it.inputTokens + it.outputTokens },
        fallback = { TokenBudgetTracker.aggregate(conversation).totalTokens },
    )

/** Ledger total when there is one, else the [fallback] estimate. */
internal fun usedTokensOrFallback(ledgerTokens: Long, fallback: () -> Long): Long =
    if (ledgerTokens > 0L) ledgerTokens else fallback()

/** "Assistant / Model / N tokens", the subtitle line of the regular chat top bar. */
internal fun topBarSubtitle(assistantName: String, modelName: String, usedTokens: Long): String =
    "$assistantName / $modelName / $usedTokens ${tokenUnit(usedTokens)}"

/**
 * The unit word after a token count: "token" for exactly one, "tokens" otherwise (0, 2, and
 * every 万 / 亿 / K value). The word stays English in every locale, as before.
 */
internal fun tokenUnit(count: Long): String = if (count == 1L) "token" else "tokens"

/**
 * Token part of the clean-layout subtitle, " / N tokens", kept as its own text so a long model
 * name cannot ellipsize the count away. Chinese locales count in 万 / 亿 ([formatTokensChinese],
 * 萬 / 億 when [traditional]); others use the compact K / M form of the statistics page
 * ([formatCompactTokens]).
 */
internal fun cleanTopBarTokenText(usedTokens: Long, chinese: Boolean, traditional: Boolean = false): String {
    val count = if (chinese) formatTokensChinese(usedTokens, traditional) else formatCompactTokens(usedTokens)
    return " / $count ${tokenUnit(usedTokens)}"
}

/** Whether [locale] reads Chinese, and whether in traditional characters (zh-TW, zh-HK, zh-Hant). */
internal fun isChineseLocale(locale: Locale): Boolean = locale.language == "zh"

internal fun isTraditionalChineseLocale(locale: Locale): Boolean =
    isChineseLocale(locale) && (locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO"))

/**
 * [count] the Chinese way: the plain number below 10000, then 万 (10^4) and 亿 (10^8) with up to
 * two decimals (half-up) and no trailing zeros, e.g. 143793 -> "14.38万", 100000 -> "10万".
 * A 万 value that rounds up to 10000 is shown as 亿 instead ("1亿", never "10000万").
 */
internal fun formatTokensChinese(count: Long, traditional: Boolean = false): String {
    if (count < 10_000L) return count.toString()
    val wan = scaled(count, 10_000L)
    if (wan < BigDecimal(10_000)) return plain(wan) + if (traditional) "萬" else "万"
    return plain(scaled(count, 100_000_000L)) + if (traditional) "億" else "亿"
}

private fun scaled(count: Long, unit: Long): BigDecimal =
    BigDecimal.valueOf(count).divide(BigDecimal.valueOf(unit), 2, RoundingMode.HALF_UP)

private fun plain(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()
