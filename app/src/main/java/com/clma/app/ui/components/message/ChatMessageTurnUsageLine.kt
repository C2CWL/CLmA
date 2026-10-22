package me.rerere.rikkahub.ui.components.message

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.CoinsDollar
import me.rerere.hugeicons.stroke.Cpu
import me.rerere.hugeicons.stroke.Download04
import me.rerere.hugeicons.stroke.Upload02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.usage.TurnFooter
import me.rerere.rikkahub.data.usage.UsageCallView
import me.rerere.rikkahub.data.usage.UsagePurpose
import me.rerere.rikkahub.utils.formatNumber
import me.rerere.rikkahub.utils.toFixed
import kotlin.math.roundToInt

/**
 * P2-12b / D6 — the turn view, the single footer under an assistant reply.
 *
 * Collapsed it is one summary line (`N calls` + totals + the turn's elapsed time); tapping it
 * expands the per-call list. Two things are live (D6):
 *
 *  - `usage` comes from [TurnFooter] and is re-read while the turn runs, so a call that has just
 *    completed shows up without waiting for the tool loop to end;
 *  - the elapsed counter ticks once a second while [TurnFooter.running], then freezes on the
 *    turn's finished value.
 *
 * When the ledger has no row for a still-running turn, `usage` is null and the line shows
 * “counting…” instead of an empty or stale summary.
 */
@Composable
internal fun TurnUsageLine(
    footer: TurnFooter,
    modifier: Modifier = Modifier,
) {
    val usage = footer.usage
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.padding(horizontal = 4.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable(enabled = usage != null) { expanded = !expanded },
        ) {
            if (usage == null) {
                Text(text = stringResource(R.string.chat_message_turn_pending))
            } else {
                Text(text = stringResource(R.string.chat_message_turn_calls, usage.callCount))
                StatsItem(
                    icon = {
                        Icon(
                            imageVector = HugeIcons.Upload02,
                            contentDescription = stringResource(R.string.accessibility_input_tokens),
                            modifier = Modifier.size(12.dp),
                        )
                    },
                    content = { Text(text = "${usage.inputTokens.tokensText()} tokens") },
                )
                // The split is only meaningful when at least one call reported cache fields; a
                // provider that stays silent must not be rendered as a 0% hit rate.
                if (usage.cacheReported && usage.cachePromptTokens > 0) {
                    val hit = usage.cacheHitTokens.coerceAtMost(usage.cachePromptTokens)
                    val miss = usage.cachePromptTokens - hit
                    val rate = hit * 100 / usage.cachePromptTokens
                    Text(text = "(${hit.tokensText()} hit / ${miss.tokensText()} miss / $rate%)")
                }
                StatsItem(
                    icon = {
                        Icon(
                            imageVector = HugeIcons.Download04,
                            contentDescription = stringResource(R.string.accessibility_output_tokens),
                            modifier = Modifier.size(12.dp),
                        )
                    },
                    content = { Text(text = "${usage.outputTokens.tokensText()} tokens") },
                )
                usage.tokensPerSecond?.let { rate ->
                    StatsItem(
                        icon = {
                            Icon(
                                imageVector = HugeIcons.Cpu,
                                contentDescription = stringResource(R.string.chat_message_turn_speed),
                                modifier = Modifier.size(12.dp),
                            )
                        },
                        content = { Text(text = "${rate.roundToInt()} tok/s") },
                    )
                }
                turnCostText(usage.providerCostUsd, usage.costMicros)?.let { cost ->
                    StatsItem(
                        icon = {
                            Icon(
                                imageVector = HugeIcons.CoinsDollar,
                                contentDescription = stringResource(R.string.accessibility_cost),
                                modifier = Modifier.size(12.dp),
                            )
                        },
                        content = { Text(text = cost) },
                    )
                }
            }
            TurnElapsedItem(footer)
            if (usage != null) {
                Icon(
                    imageVector = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                    contentDescription = stringResource(
                        if (expanded) R.string.chat_message_turn_collapse
                        else R.string.chat_message_turn_expand
                    ),
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        if (usage != null) {
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(top = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    usage.calls.forEach { call -> UsageCallRow(call) }
                    // The list can run longer than the screen, so the summary line's chevron —
                    // the only way back — may be scrolled out of sight. Mirror it at the bottom
                    // of the list so collapsing never means scrolling up first.
                    CollapseUsageRow(onCollapse = { expanded = false })
                }
            }
        }
    }
}

/**
 * The bottom mirror of the summary row's chevron: one tap anywhere on the line folds the
 * per-call list away again, without having to scroll back up to the summary. Tinted with the
 * primary colour (like the stats page's `Show less`) so it reads as a control rather than as
 * one more muted stat.
 */
@Composable
private fun CollapseUsageRow(onCollapse: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .clickable(onClick = onCollapse)
            .padding(top = 2.dp),
    ) {
        Icon(
            imageVector = HugeIcons.ArrowUp01,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = stringResource(R.string.chat_message_turn_collapse_action),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * D6 — the turn's elapsed wall clock. While the turn is running the value ticks once a second;
 * once it has finished it is the frozen `finishedAt - startedAt`. A turn whose end we cannot
 * know (stopped / failed, no `finishedAt`) shows nothing rather than a made-up number.
 */
@Composable
private fun TurnElapsedItem(footer: TurnFooter) {
    // Always called, so the slot stays stable across the running → finished transition; it only
    // subscribes to a clock while the turn is actually running.
    val nowMs = rememberTickingNowMs(enabled = footer.running)
    if (footer.finishedAtMs == null && !footer.running) return
    val endMs = footer.finishedAtMs ?: nowMs
    val elapsedMs = (endMs - footer.startedAtMs).coerceAtLeast(0L)
    StatsItem(
        icon = {
            Icon(
                imageVector = HugeIcons.Clock02,
                contentDescription = stringResource(R.string.chat_message_turn_elapsed),
                modifier = Modifier.size(12.dp),
            )
        },
        content = { Text(text = formatElapsed(elapsedMs)) },
    )
}

@Composable
private fun rememberTickingNowMs(enabled: Boolean): Long {
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    return nowMs
}

/** `12.4s` under a minute, `1m 05s` above — a long tool-loop turn stays readable. */
private fun formatElapsed(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    return if (minutes <= 0L) {
        (ms / 1000.0).toFixed(1) + "s"
    } else {
        val seconds = totalSeconds % 60
        "${minutes}m ${seconds.toString().padStart(2, '0')}s"
    }
}

@Composable
private fun UsageCallRow(call: UsageCallView) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(usagePurposeLabel(call.purpose)),
            modifier = Modifier.padding(end = 2.dp),
        )
        Text(text = "${call.inputTokens.formatNumber()}→${call.outputTokens.formatNumber()}")
        if (call.cachedTokensReported && call.cachedTokens > 0) {
            Text(text = "(${call.cachedTokens.formatNumber()} hit)")
        }
        // No per-call cost: the collapsed summary above already carries the turn total. With it,
        // this row was the widest thing on a 384dp screen and its trailing `tok/s` wrapped onto a
        // second line; dropping it (plus the arrow spacing and the `ms` suffix) keeps one line.
        call.latencyMs?.let { ms ->
            Text(text = formatElapsed(ms))
            if (ms > 0) Text(text = "${(call.outputTokens * 1000.0 / ms).roundToInt()} tok/s")
        }
    }
}

/**
 * `~` marks a cost the local price table computed; a provider-reported cost is shown bare.
 * Unpriced calls render nothing rather than "$0" (P2-11: a missing price is not free).
 */
private fun turnCostText(providerCostUsd: Double?, costMicros: Long?): String? {
    if (providerCostUsd != null && providerCostUsd > 0.0) return formatCost(providerCostUsd)
    if (costMicros != null && costMicros > 0L) return "~" + formatCost(costMicros / 1_000_000.0)
    return null
}

private fun usagePurposeLabel(purpose: String): Int = when (purpose) {
    UsagePurpose.MAIN.name -> R.string.chat_message_usage_purpose_main
    UsagePurpose.TOOL_LOOP.name -> R.string.chat_message_usage_purpose_tool_loop
    UsagePurpose.COMPACTION.name -> R.string.chat_message_usage_purpose_compaction
    UsagePurpose.MEMORY_EXTRACT.name -> R.string.chat_message_usage_purpose_memory_extract
    UsagePurpose.SUBAGENT.name -> R.string.chat_message_usage_purpose_subagent
    UsagePurpose.CRON.name -> R.string.chat_message_usage_purpose_cron
    UsagePurpose.WORKFLOW.name -> R.string.chat_message_usage_purpose_workflow
    UsagePurpose.SKILL_TEST.name -> R.string.chat_message_usage_purpose_skill_test
    UsagePurpose.TRANSLATION.name -> R.string.chat_message_usage_purpose_translation
    else -> R.string.chat_message_usage_purpose_unknown
}

/** `formatNumber()` is Int-only; turn totals are Long, so clamp before formatting. */
private fun Long.tokensText(): String =
    if (this in 0..Int.MAX_VALUE.toLong()) toInt().formatNumber() else toString()
