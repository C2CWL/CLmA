package me.rerere.rikkahub.ui.pages.stats

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.ChartColumn
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.Message01
import me.rerere.hugeicons.stroke.Robot01
import me.rerere.hugeicons.stroke.Rocket01
import me.rerere.hugeicons.stroke.Zap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.usage.LedgerStatsView
import me.rerere.rikkahub.data.usage.OrchestrationNode
import me.rerere.rikkahub.data.usage.OrchestrationTree
import me.rerere.rikkahub.data.usage.UsageDaySeries
import me.rerere.rikkahub.data.usage.UsagePurpose
import me.rerere.rikkahub.data.usage.UsagePurposeGroups
import me.rerere.rikkahub.data.usage.UsageStatBucket
import me.rerere.rikkahub.data.usage.UsageStatsFactory
import me.rerere.rikkahub.ui.components.message.formatCost
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

@Composable
fun StatsPage(vm: StatsVM = koinViewModel()) {
    val stats by vm.stats.collectAsStateWithLifecycle()
    val ledgerStats by vm.ledgerStats.collectAsStateWithLifecycle()
    val assistantNames by vm.assistantNames.collectAsStateWithLifecycle()
    val orchestrationTrees by vm.orchestrationTrees.collectAsStateWithLifecycle()
    val conversationTitles by vm.conversationTitles.collectAsStateWithLifecycle()
    val range by vm.range.collectAsStateWithLifecycle()

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.stats_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        if (stats.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = padding + PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    StatsRangeSelector(
                        selected = range,
                        onSelect = vm::setRange,
                    )
                }
                ledgerStats?.let { ledger ->
                    item {
                        DailyUsageChartCard(
                            byDay = ledger.byDay,
                            windowDays = range.days,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                    item {
                        StatsCallsCard(
                            byDay = ledger.byDay,
                            windowDays = range.days,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                }
                item {
                    Column(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StatsSectionHeader(
                            icon = HugeIcons.ChartColumn,
                            title = stringResource(R.string.stats_page_overview_title),
                            trailing = stringResource(R.string.stats_page_overview_window_all),
                        )
                        StatsOverview(stats = stats)
                    }
                }
                ledgerStats?.let { ledger ->
                    item {
                        LedgerStatsSection(
                            view = ledger,
                            assistantNames = assistantNames,
                            windowDays = range.days,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                }
                orchestrationTrees?.let { trees ->
                    item {
                        OrchestrationSection(
                            trees = trees,
                            conversationTitles = conversationTitles,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * D10 - the ledger window as a chart, in place of the old conversation heatmap.
 *
 * One card carries the trend and its summary: four numbers across the top that the bar shape
 * alone cannot show (window total, daily average, busiest day, calls), a stacked bar per day
 * with output on top of input so each bar reads as one total, and a line of that day's call
 * count beneath it on the same x-axis. Bars scale to the busiest day, the line to its own peak,
 * so neither flattens the other.
 *
 * The factory hands [byDay] newest first; the chart reads left to right, so it is reversed.
 */
@Composable
private fun DailyUsageChartCard(
    byDay: List<UsageStatBucket>,
    windowDays: Int,
    modifier: Modifier = Modifier,
) {
    // The ledger's byDay ranking is sparse: only days with rows get a bucket. For a trend chart
    // that silently stitches active days together, so the series is filled to a continuous
    // calendar first — a quiet day is a zero bar, and the average below divides by the window
    // length rather than by the number of days that happened to have rows.
    val days = remember(byDay, windowDays) {
        UsageDaySeries.fillWindow(byDay, windowDays, LocalDate.now()).reversed()
    }

    Card(modifier = modifier.fillMaxWidth(), colors = CustomColors.cardColorsOnSurfaceContainer) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.stats_page_daily_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.stats_page_daily_window, windowDays),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (byDay.isEmpty()) {
                Text(
                    text = stringResource(R.string.stats_page_ledger_empty, windowDays),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val totalTokens = days.sumOf { it.totalTokens }
                val totalCalls = days.sumOf { it.callCount }
                val busiest = days.maxByOrNull { it.totalTokens }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DailyMetric(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.stats_page_daily_total),
                        value = formatCompactTokens(totalTokens),
                    )
                    DailyMetric(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.stats_page_daily_average),
                        // Calendar average: the filled series always spans the whole window.
                        value = formatCompactTokens(totalTokens / days.size),
                    )
                    DailyMetric(
                        modifier = Modifier.weight(1f),
                        // The date matters as much as the figure — "MM-dd" from the ISO key.
                        label = busiest?.let {
                            stringResource(R.string.stats_page_daily_peak_on, it.key.takeLast(5))
                        } ?: stringResource(R.string.stats_page_daily_peak),
                        value = formatCompactTokens(busiest?.totalTokens ?: 0L),
                    )
                    DailyMetric(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.stats_page_daily_calls),
                        value = formatCompactCount(totalCalls.toLong()),
                    )
                }

                val inputColor = MaterialTheme.colorScheme.primary
                val outputColor = MaterialTheme.colorScheme.tertiary

                StatsDailyChart(
                    days = days,
                    inputColor = inputColor,
                    outputColor = outputColor,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    LegendDot(color = inputColor, label = stringResource(R.string.stats_page_input_tokens))
                    LegendDot(color = outputColor, label = stringResource(R.string.stats_page_output_tokens))
                }
            }
        }
    }
}

@Composable
private fun StatsRangeSelector(
    selected: StatsRange,
    onSelect: (StatsRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatsRange.entries.forEach { range ->
            FilterChip(
                selected = range == selected,
                onClick = { onSelect(range) },
                label = { Text(stringResource(R.string.stats_page_daily_window, range.days)) },
            )
        }
    }
}

/**
 * The call-count trend as its own card, mirroring the "API requests" card on the DeepSeek
 * dashboard: a big total on top, an area line beneath it.
 */
@Composable
private fun StatsCallsCard(
    byDay: List<UsageStatBucket>,
    windowDays: Int,
    modifier: Modifier = Modifier,
) {
    // Same fill as the token chart: quiet days are zero points, not missing ones.
    val days = remember(byDay, windowDays) {
        UsageDaySeries.fillWindow(byDay, windowDays, LocalDate.now()).reversed()
    }
    Card(modifier = modifier.fillMaxWidth(), colors = CustomColors.cardColorsOnSurfaceContainer) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.stats_page_calls_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.stats_page_daily_window, windowDays),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (byDay.isEmpty()) {
                Text(
                    text = stringResource(R.string.stats_page_ledger_empty, windowDays),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = formatCompactCount(days.sumOf { it.callCount }.toLong()),
                    style = MaterialTheme.typography.headlineMedium,
                )
                StatsCallsChart(
                    days = days,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun DailyMetric(modifier: Modifier = Modifier, label: String, value: String) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = value, style = MaterialTheme.typography.titleSmall)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * D10b - one heading style for every grouped block on the page, so the overview, ledger and
 * orchestration sections read as siblings rather than three differently-styled headers.
 */
@Composable
private fun StatsSectionHeader(
    icon: ImageVector,
    title: String,
    trailing: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One cell of the overview grid: an icon, a big number and the label under it. */
private data class OverviewMetric(
    val icon: ImageVector,
    val label: String,
    val value: String,
)

/**
 * The all-time overview: every figure covers the whole message history (or the settings store's
 * launch counter), so the section has a single time scope and the header can say so. The
 * windowed token figures live in the ledger section below — an earlier revision swapped these
 * cards to the ledger's window total once it loaded, which mixed two time scopes on one screen
 * and made the numbers jump as the load finished.
 *
 * No all-time cache hit rate: the message DB cannot tell "provider reported no caching" from
 * "provider does not report cache fields", so the rate would read low for no real reason (the
 * P2-11a rule). The windowed rate, computed over reporting rows only, is on the ledger summary
 * card instead.
 */
@Composable
private fun StatsOverview(stats: AppStats, modifier: Modifier = Modifier) {
    // `listOfNotNull` (not `buildList`) so every `stringResource` is evaluated in the composable
    // scope at this call site rather than inside a non-composable builder lambda.
    val metrics = listOfNotNull(
        OverviewMetric(
            icon = HugeIcons.ChartColumn,
            label = stringResource(R.string.stats_page_total_conversations),
            value = formatCompactCount(stats.totalConversations.toLong()),
        ),
        OverviewMetric(
            icon = HugeIcons.Message01,
            label = stringResource(R.string.stats_page_total_messages),
            value = formatCompactCount(stats.totalMessages.toLong()),
        ),
        OverviewMetric(
            icon = HugeIcons.ArrowDown01,
            label = stringResource(R.string.stats_page_input_tokens),
            value = formatCompactTokens(stats.totalPromptTokens),
        ),
        OverviewMetric(
            icon = HugeIcons.ArrowUp01,
            label = stringResource(R.string.stats_page_output_tokens),
            value = formatCompactTokens(stats.totalCompletionTokens),
        ),
        if (stats.totalCachedTokens > 0) {
            OverviewMetric(
                icon = HugeIcons.Zap,
                label = stringResource(R.string.stats_page_cached_tokens),
                value = formatCompactTokens(stats.totalCachedTokens),
            )
        } else null,
        OverviewMetric(
            icon = HugeIcons.Rocket01,
            label = stringResource(R.string.stats_page_launch_count),
            value = formatCompactCount(stats.launchCount.toLong()),
        ),
    )

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        metrics.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (pair.size == 2) {
                    StatCard(metric = pair[0], modifier = Modifier.weight(1f))
                    StatCard(metric = pair[1], modifier = Modifier.weight(1f))
                } else {
                    StatCardWide(metric = pair[0], modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun StatCard(metric: OverviewMetric, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CustomColors.cardColorsOnSurfaceContainer) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MetricIcon(metric.icon)
            Text(
                text = metric.value,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = metric.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The odd metric out: a full-width card with the icon beside the number rather than above it. */
@Composable
private fun StatCardWide(metric: OverviewMetric, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CustomColors.cardColorsOnSurfaceContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MetricIcon(metric.icon)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = metric.value,
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = metric.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The soft round chip every overview card leads with, so the icons read as one family. */
@Composable
private fun MetricIcon(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(16.dp),
        )
    }
}

// ---- P2-12c: the usage-ledger section ---------------------------------------------------------

/** Rows shown per dimension before the "show all" toggle appears. */
private const val LEDGER_COLLAPSED_ROWS = 5

private const val ORCHESTRATION_COLLAPSED_ROWS = 5

/**
 * P2-12d — the parent→child orchestration tree: the newest sub-agent dispatches, one card per
 * parent conversation.
 *
 * Kept apart from the ledger rankings on purpose: those aggregate every model call in the window by
 * one dimension, while this shows *who dispatched whom*. A run with no ledger rows still appears
 * (with zero calls), because the dispatch itself is the interesting event — the missing rows only
 * say the calls came in before P2-12d started recording a run id.
 */
@Composable
private fun OrchestrationSection(
    trees: List<OrchestrationTree>,
    conversationTitles: Map<String, String>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatsSectionHeader(
            icon = HugeIcons.Robot01,
            title = stringResource(R.string.stats_page_orchestration_title),
            trailing = stringResource(R.string.stats_page_orchestration_window),
        )

        if (trees.isEmpty()) {
            Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                Text(
                    text = stringResource(R.string.stats_page_orchestration_empty),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            trees.forEach { tree ->
                OrchestrationTreeCard(
                    tree = tree,
                    parentTitle = tree.parentConversationId?.let { conversationTitles[it] },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun OrchestrationTreeCard(
    tree: OrchestrationTree,
    parentTitle: String?,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val shown = if (expanded) tree.children else tree.children.take(ORCHESTRATION_COLLAPSED_ROWS)

    Card(modifier = modifier, colors = CustomColors.cardColorsOnSurfaceContainer) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = parentTitle
                        ?: stringResource(R.string.stats_page_orchestration_unknown_parent),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.stats_page_orchestration_children, tree.childCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatCompactTokens(tree.totalTokens),
                    style = MaterialTheme.typography.labelSmall,
                )
                ledgerCostText(tree.providerCostUsd, tree.costMicros)?.let { cost ->
                    Text(text = cost, style = MaterialTheme.typography.labelSmall)
                }
            }

            // P2-14d — the budget footer, shown only when a ceiling is configured. It is the one
            // place the user can see how close an orchestration is to the P2-13 refusal, which the
            // model itself never learns until it is turned away.
            if (tree.hasBudget) {
                Text(
                    text = stringResource(
                        R.string.stats_page_orchestration_budget,
                        formatCompactTokens(tree.totalTokens),
                        formatCompactTokens(tree.budget ?: 0L),
                        formatCompactTokens(tree.remaining ?: 0L),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (tree.budgetExceeded) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            shown.forEach { child ->
                OrchestrationChildRow(child)
            }

            if (tree.children.size > ORCHESTRATION_COLLAPSED_ROWS) {
                val toggle = if (expanded) {
                    stringResource(R.string.stats_page_orchestration_collapse)
                } else {
                    stringResource(R.string.stats_page_orchestration_expand, tree.children.size)
                }
                Text(
                    text = toggle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { expanded = !expanded },
                )
            }
        }
    }
}

@Composable
private fun OrchestrationChildRow(node: OrchestrationNode) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    if (node.isRunning) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    }
                ),
        )
        Text(
            text = node.label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = node.status,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = if (node.hasLedgerRows) {
                stringResource(R.string.stats_page_orchestration_calls, node.callCount)
            } else {
                stringResource(R.string.stats_page_orchestration_no_calls)
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = formatCompactTokens(node.totalTokens),
            style = MaterialTheme.typography.labelSmall,
        )
        ledgerCostText(node.providerCostUsd, node.costMicros)?.let { cost ->
            Text(text = cost, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/**
 * The rankings of the ledger window, headed by a totals card. [StatsOverview]'s token cards read
 * the same ledger grand total since D1; only its conversation / message / launch counts still come
 * from the message and settings stores, so no number here is ever added to a differently-windowed
 * one.
 */
@Composable
private fun LedgerStatsSection(
    view: LedgerStatsView,
    assistantNames: Map<String, String>,
    windowDays: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatsSectionHeader(
            icon = HugeIcons.Database02,
            title = stringResource(R.string.stats_page_ledger_title),
            // The real selected window — the header used to claim "last 90 days" regardless.
            trailing = stringResource(R.string.stats_page_daily_window, windowDays),
        )

        if (view.total.callCount == 0) {
            Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                Text(
                    text = stringResource(R.string.stats_page_ledger_empty, windowDays),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LedgerSummaryCard(view = view, modifier = Modifier.fillMaxWidth())
            LedgerBucketCard(
                title = stringResource(R.string.stats_page_ledger_by_day),
                buckets = view.byDay,
                view = view,
                labelOf = { it },
                modifier = Modifier.fillMaxWidth(),
            )
            LedgerBucketCard(
                title = stringResource(R.string.stats_page_ledger_by_purpose),
                // D10 - the five coarse groups rather than one row per call site; the factory
                // still emits the raw purposes, so only this page changes.
                buckets = UsagePurposeGroups.merge(view.byPurpose),
                view = view,
                labelOf = { key -> stringResource(usageGroupLabelRes(key)) },
                modifier = Modifier.fillMaxWidth(),
            )
            LedgerBucketCard(
                title = stringResource(R.string.stats_page_ledger_by_provider),
                buckets = view.byProvider,
                view = view,
                labelOf = { key ->
                    if (key == UsageStatsFactory.UNKNOWN_KEY) {
                        stringResource(R.string.stats_page_ledger_unknown)
                    } else {
                        key
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            LedgerBucketCard(
                title = stringResource(R.string.stats_page_ledger_by_model),
                buckets = view.byModel,
                view = view,
                labelOf = { key ->
                    if (key == UsageStatsFactory.UNKNOWN_KEY) {
                        stringResource(R.string.stats_page_ledger_unknown)
                    } else {
                        key
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            LedgerBucketCard(
                title = stringResource(R.string.stats_page_ledger_by_assistant),
                buckets = view.byAssistant,
                view = view,
                labelOf = { key ->
                    assistantNames[key]
                        ?: if (key == UsageStatsFactory.UNKNOWN_KEY) {
                            stringResource(R.string.stats_page_ledger_unknown)
                        } else {
                            key.take(8)
                        }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * The window's headline figures, in one card above the rankings: how many calls, how many tokens,
 * and what they cost. The rankings below answer *where* the spend went; this answers *how much*,
 * which otherwise had to be rebuilt by adding up the ranked rows by hand. Every figure is the same
 * grand total the rankings are a share of, so the card can never disagree with them.
 *
 * The price follows the ledger's long-standing rule (see [ledgerCostText]): a provider-reported
 * cost is shown bare, a table-computed one gets a `~`, and a window nobody could price simply
 * leaves the cell out instead of claiming "$0".
 */
@Composable
private fun LedgerSummaryCard(view: LedgerStatsView, modifier: Modifier = Modifier) {
    val total = view.total
    val cost = ledgerCostText(total.providerCostUsd, total.costMicros)

    Card(modifier = modifier, colors = CustomColors.cardColorsOnSurfaceContainer) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.stats_page_ledger_summary_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DailyMetric(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.stats_page_ledger_summary_calls),
                    value = formatCompactCount(total.callCount.toLong()),
                )
                DailyMetric(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.stats_page_ledger_summary_tokens),
                    value = formatCompactTokens(total.totalTokens),
                )
                if (cost != null) {
                    DailyMetric(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.stats_page_ledger_summary_cost),
                        value = cost,
                    )
                }
            }
            // The two rates are shown on their own line, and only when they are knowable: a
            // cache rate with no reporter and a throughput with no measured latency both stay
            // off rather than reading as a real zero (the bucket rule, applied to the total).
            val cacheRate = total.cacheHitRate
            val rate = total.tokensPerSecond
            if (cacheRate != null || rate != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (cacheRate != null) {
                        Text(
                            text = stringResource(R.string.stats_page_ledger_cache_hit, cacheRate),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (rate != null) {
                        Text(
                            text = stringResource(R.string.stats_page_ledger_tok_per_sec, formatTokenRate(rate)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LedgerBucketCard(
    title: String,
    buckets: List<UsageStatBucket>,
    view: LedgerStatsView,
    labelOf: @Composable (String) -> String,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val shown = if (expanded) buckets else buckets.take(LEDGER_COLLAPSED_ROWS)

    Card(modifier = modifier, colors = CustomColors.cardColorsOnSurfaceContainer) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)

            shown.forEach { bucket ->
                LedgerBucketRow(
                    bucket = bucket,
                    share = view.shareOfTokens(bucket),
                    label = labelOf(bucket.key),
                )
            }

            if (buckets.size > LEDGER_COLLAPSED_ROWS) {
                val toggle = if (expanded) {
                    stringResource(R.string.stats_page_ledger_collapse)
                } else {
                    stringResource(R.string.stats_page_ledger_expand, buckets.size)
                }
                Text(
                    text = toggle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { expanded = !expanded },
                )
            }
        }
    }
}

@Composable
private fun LedgerBucketRow(
    bucket: UsageStatBucket,
    share: Float,
    label: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // D10b - the label owns its own line and the numbers move below the share bar: five
        // figures crammed onto one row clipped the label on a narrow phone and made the token
        // count hard to scan against its neighbours.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatCompactTokens(bucket.totalTokens),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(share.coerceIn(0f, 1f))
                    .height(4.dp)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.stats_page_ledger_calls, bucket.callCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // D6 — throughput, same rule as the message footer (P2-19): measured output
            // tokens over the summed per-call latency. Null (nobody reported a latency)
            // shows nothing rather than a made-up 0.
            bucket.tokensPerSecond?.let { rate ->
                Text(
                    text = stringResource(R.string.stats_page_ledger_tok_per_sec, formatTokenRate(rate)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // The cost is right-aligned on the footer line, so a column of rows reads as a
            // right-aligned price list even when the middle figures are missing.
            val cost = ledgerCostText(bucket.providerCostUsd, bucket.costMicros)
            if (cost != null) {
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = cost,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

/**
 * Same rule as the message footer (P2-12b): a provider-reported cost is shown bare, a cost
 * recomputed from the local price table gets a `~`, and a call nobody could price shows nothing
 * rather than "$0" - a missing price is not free.
 */
private fun ledgerCostText(providerCostUsd: Double?, costMicros: Long?): String? {
    if (providerCostUsd != null && providerCostUsd > 0.0) return formatCost(providerCostUsd)
    if (costMicros != null && costMicros > 0L) return "~" + formatCost(costMicros / 1_000_000.0)
    return null
}

/**
 * D10 - the five coarse groups the by-purpose ranking is shown under. The per-call-site labels
 * (chat_message_usage_purpose_*) still head each row in the message footer; this page shows
 * where the spend went, not which call site ran.
 */
private fun usageGroupLabelRes(group: String): Int = when (group) {
    UsagePurposeGroups.CONVERSATION -> R.string.stats_page_usage_group_conversation
    UsagePurposeGroups.SUBAGENT -> R.string.stats_page_usage_group_subagent
    UsagePurposeGroups.AUTOMATION -> R.string.stats_page_usage_group_automation
    UsagePurposeGroups.ASSISTANT -> R.string.stats_page_usage_group_assistant
    else -> R.string.stats_page_usage_group_other
}
