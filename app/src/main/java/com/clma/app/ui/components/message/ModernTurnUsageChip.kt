package com.clma.app.ui.components.message

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.clma.app.R
import com.clma.app.data.usage.TurnFooter
import com.clma.app.data.usage.TurnUsageView
import com.clma.app.data.usage.UsageCallView
import com.clma.app.ui.components.ui.FrostedPlateSpec
import com.clma.app.ui.components.ui.frostedPlate
import com.clma.app.ui.components.ui.modernTonal
import com.clma.app.ui.pages.chat.compactTokenCountText
import com.clma.app.ui.pages.chat.isChineseLocale
import com.clma.app.ui.pages.chat.isTraditionalChineseLocale
import com.clma.app.utils.formatNumber
import com.clma.app.utils.toFixed
import dev.chrisbanes.haze.HazeState
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CoinsDollar
import kotlin.math.roundToInt

private val UsageChipCorner = RoundedCornerShape(10.dp)
private val UsagePopoverCorner = RoundedCornerShape(12.dp)

/**
 * Modern-layout reply footer: a compact tonal chip (finish time + total tokens) on the action
 * row. Tap opens an anchored 「本回合用量」 popover with a 2-column summary grid (and optional
 * per-call rows). Wallpaper uses the same frost/plate treatment as [ChatMessageNerdLine]; the
 * popover stays an opaque elevated surface so text stays readable over a photo.
 */
@Composable
internal fun ModernTurnUsageChip(
    footer: TurnFooter,
    finishTime: String?,
    photoShown: Boolean = false,
    hazeState: HazeState? = null,
    modifier: Modifier = Modifier,
) {
    val usage = footer.usage
    if (usage == null) {
        // No ledger yet: show finish time alone (or “counting…” while the turn runs). No action.
        val pending = footer.running
        val label = when {
            finishTime != null -> finishTime
            pending -> stringResource(R.string.chat_message_turn_pending)
            else -> null
        } ?: return
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            maxLines = 1,
            modifier = modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
        return
    }

    var expanded by remember { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    val total = usage.inputTokens + usage.outputTokens
    val totalText = compactTokenCountText(
        usedTokens = total,
        chinese = isChineseLocale(locale),
        traditional = isTraditionalChineseLocale(locale),
    )
    val style = usageLineStyle(photoShown, sdkInt = Build.VERSION.SDK_INT, hasHazeState = hazeState != null)
    val ink = when (style.ink) {
        UsageLineInk.ON_SURFACE -> MaterialTheme.colorScheme.onSurface
        UsageLineInk.SECONDARY -> MaterialTheme.colorScheme.onSurfaceVariant
    }.copy(alpha = if (photoShown) style.inkAlpha else 0.85f)

    Box(modifier = modifier) {
        UsageChipSurface(
            photoShown = photoShown,
            style = style,
            hazeState = hazeState,
            onClick = { expanded = true },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            ) {
                if (finishTime != null) {
                    Text(
                        text = finishTime,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                        color = ink,
                        maxLines = 1,
                    )
                    Box(
                        modifier = Modifier
                            .size(width = 1.dp, height = 11.dp)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)),
                    )
                }
                Icon(
                    imageVector = HugeIcons.CoinsDollar,
                    contentDescription = stringResource(R.string.chat_message_turn_tokens_a11y),
                    modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = totalText,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = UsagePopoverCorner,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 0.dp,
            shadowElevation = 10.dp,
            border = null,
        ) {
            TurnUsagePopoverContent(usage = usage, footer = footer)
        }
    }
}

@Composable
private fun UsageChipSurface(
    photoShown: Boolean,
    style: UsageLineStyle,
    hazeState: HazeState?,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val frost = style.frost
    val plateAlpha = style.plateAlpha
    when {
        frost != null && hazeState != null -> {
            Box(
                modifier = Modifier
                    .frostedPlate(
                        hazeState = hazeState,
                        shape = UsageChipCorner,
                        spec = FrostedPlateSpec(tintAlpha = frost.tintAlpha, blurRadiusDp = frost.blurRadiusDp),
                        selection = WallpaperOnlySelection,
                    )
                    .clip(UsageChipCorner)
                    .clickable(onClick = onClick),
            ) { content() }
        }
        plateAlpha != null -> {
            Surface(
                onClick = onClick,
                shape = UsageChipCorner,
                color = MaterialTheme.colorScheme.surface.copy(alpha = plateAlpha),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) { content() }
        }
        else -> {
            Surface(
                onClick = onClick,
                shape = UsageChipCorner,
                color = modernTonal(),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) { content() }
        }
    }
}

@Composable
private fun TurnUsagePopoverContent(usage: TurnUsageView, footer: TurnFooter) {
    val locale = LocalConfiguration.current.locales[0]
    val chinese = isChineseLocale(locale)
    val traditional = isTraditionalChineseLocale(locale)
    fun tok(n: Long) = compactTokenCountText(n, chinese, traditional)

    Column(
        modifier = Modifier
            .widthIn(min = 200.dp, max = 280.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.chat_message_turn_usage_title),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
        )

        val cacheText = if (usage.cacheReported && usage.cachePromptTokens > 0) {
            val hit = usage.cacheHitTokens.coerceAtLeast(0).coerceAtMost(usage.cachePromptTokens)
            val miss = usage.cachePromptTokens - hit
            val rate = hit * 100 / usage.cachePromptTokens
            stringResource(
                R.string.chat_message_turn_cache_detail,
                rate,
                tok(hit),
                tok(miss),
            )
        } else {
            stringResource(R.string.chat_message_turn_cache_unknown)
        }
        val speedText = usage.tokensPerSecond?.let { "${it.roundToInt()} tok/s" }
            ?: stringResource(R.string.chat_message_turn_value_na)
        val elapsedText = formatTurnElapsed(footer)
            ?: stringResource(R.string.chat_message_turn_value_na)
        val costText = turnCostText(usage.providerCostUsd, usage.costMicros)

        val cells = buildList {
            add(stringResource(R.string.chat_message_turn_grid_calls) to stringResource(R.string.chat_message_turn_calls_short, usage.callCount))
            add(stringResource(R.string.chat_message_turn_grid_input) to tok(usage.inputTokens))
            add(stringResource(R.string.chat_message_turn_grid_output) to tok(usage.outputTokens))
            add(stringResource(R.string.chat_message_turn_grid_cache) to cacheText)
            add(stringResource(R.string.chat_message_turn_grid_speed) to speedText)
            add(stringResource(R.string.chat_message_turn_grid_elapsed) to elapsedText)
            if (costText != null) {
                add(stringResource(R.string.chat_message_turn_grid_cost) to costText)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            cells.chunked(2).forEach { row ->
                Row(
                    modifier = Modifier.widthIn(max = 280.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    row.forEach { (label, value) ->
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                            )
                            Text(
                                text = value,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                            )
                        }
                    }
                    if (row.size == 1) {
                        Box(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        if (usage.calls.size > 1) {
            Text(
                text = stringResource(R.string.chat_message_turn_per_call_section),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Column(
                modifier = Modifier
                    .heightIn(max = 160.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                usage.calls.forEach { call ->
                    UsageCallSummaryRow(call)
                }
            }
        }
    }
}

@Composable
private fun UsageCallSummaryRow(call: UsageCallView) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(usagePurposeLabelRes(call.purpose)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(
            text = "${call.inputTokens.formatNumber()}→${call.outputTokens.formatNumber()}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        if (call.cachedTokensReported && call.cachedTokens > 0) {
            Text(
                text = "(${call.cachedTokens.formatNumber()} hit)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        call.latencyMs?.let { ms ->
            Text(
                text = formatElapsedMs(ms),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun formatTurnElapsed(footer: TurnFooter): String? {
    if (footer.finishedAtMs == null && !footer.running) return null
    val endMs = footer.finishedAtMs ?: System.currentTimeMillis()
    val elapsedMs = (endMs - footer.startedAtMs).coerceAtLeast(0L)
    return formatElapsedMs(elapsedMs)
}

private fun formatElapsedMs(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    return if (minutes <= 0L) {
        (ms / 1000.0).toFixed(1) + "s"
    } else {
        val seconds = totalSeconds % 60
        "${minutes}m ${seconds.toString().padStart(2, '0')}s"
    }
}

private fun usagePurposeLabelRes(purpose: String): Int = when (purpose) {
    com.clma.app.data.usage.UsagePurpose.MAIN.name -> R.string.chat_message_usage_purpose_main
    com.clma.app.data.usage.UsagePurpose.TOOL_LOOP.name -> R.string.chat_message_usage_purpose_tool_loop
    com.clma.app.data.usage.UsagePurpose.COMPACTION.name -> R.string.chat_message_usage_purpose_compaction
    com.clma.app.data.usage.UsagePurpose.MEMORY_EXTRACT.name -> R.string.chat_message_usage_purpose_memory_extract
    com.clma.app.data.usage.UsagePurpose.SUBAGENT.name -> R.string.chat_message_usage_purpose_subagent
    com.clma.app.data.usage.UsagePurpose.CRON.name -> R.string.chat_message_usage_purpose_cron
    com.clma.app.data.usage.UsagePurpose.WORKFLOW.name -> R.string.chat_message_usage_purpose_workflow
    com.clma.app.data.usage.UsagePurpose.SKILL_TEST.name -> R.string.chat_message_usage_purpose_skill_test
    com.clma.app.data.usage.UsagePurpose.TRANSLATION.name -> R.string.chat_message_usage_purpose_translation
    else -> R.string.chat_message_usage_purpose_unknown
}

/** Same cost formatting as [TurnUsageLine]; kept here so the popover stays self-contained. */
private fun turnCostText(providerCostUsd: Double?, costMicros: Long?): String? {
    if (providerCostUsd != null && providerCostUsd > 0.0) return formatCost(providerCostUsd)
    if (costMicros != null && costMicros > 0L) return "~" + formatCost(costMicros / 1_000_000.0)
    return null
}

