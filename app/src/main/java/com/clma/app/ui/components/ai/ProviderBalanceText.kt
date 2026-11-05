package com.clma.app.ui.components.ai

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.clma.ai.provider.ProviderManager
import com.clma.ai.provider.ProviderSetting
import com.clma.app.R
import com.clma.app.ui.components.ui.modernStatusGreen
import com.clma.app.ui.components.ui.modernTonal
import com.clma.app.utils.SimpleCache
import kotlinx.coroutines.CancellationException
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.hugeicons.stroke.Wallet02
import org.koin.compose.koinInject
import java.util.concurrent.TimeUnit

private val cache = SimpleCache.builder<String, String>()
    .expireAfterWrite(2, TimeUnit.MINUTES)
    .build()

private val CardCorner = RoundedCornerShape(12.dp)
private val ChipCorner = RoundedCornerShape(8.dp)
private val TileCorner = RoundedCornerShape(8.dp)

private data class BalanceState(
    val text: String? = null,
    val loading: Boolean = true,
    val failed: Boolean = false,
)

/** How [ProviderBalanceText] lays out the parsed balance segments. */
enum class ProviderBalanceDisplayMode {
    /** Provider detail page: titled tonal card with labeled tiles. */
    Card,
    /** Model picker sticky header: compact tonal chips that wrap. */
    Chips,
}

@Composable
fun ProviderBalanceText(
    providerSetting: ProviderSetting,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    allowRefresh: Boolean = false,
    displayMode: ProviderBalanceDisplayMode = ProviderBalanceDisplayMode.Chips,
) {
    if (!providerSetting.balanceOption.enabled || providerSetting !is ProviderSetting.OpenAI) return

    val providerManager = koinInject<ProviderManager>()
    // Include credentials and endpoint changes without retaining the secrets in cache keys.
    val cacheKey = "${providerSetting.id}:${providerSetting.hashCode()}"
    var refresh by remember(cacheKey) { mutableIntStateOf(0) }
    var lastBalance by remember(cacheKey) { mutableStateOf(cache.getIfPresent(cacheKey)) }
    val state by produceState(BalanceState(text = lastBalance), cacheKey, refresh) {
        val cached = if (refresh == 0) cache.getIfPresent(cacheKey) else null
        if (cached != null) {
            value = BalanceState(cached, loading = false)
        } else {
            value = BalanceState(lastBalance)
            try {
                val balance = providerManager.getProviderByType(providerSetting).getBalance(providerSetting)
                cache.put(cacheKey, balance)
                lastBalance = balance
                value = BalanceState(balance, loading = false)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Responses and URLs may contain credentials; never place them in visible errors.
                value = BalanceState(lastBalance, loading = false, failed = true)
            }
        }
    }

    val rawText = state.text
    val statusMessage = when {
        state.failed && rawText == null -> stringResource(R.string.provider_balance_failed)
        state.loading && rawText == null -> stringResource(R.string.provider_balance_loading)
        else -> null
    }
    val segments = remember(rawText) {
        rawText?.let { parseProviderBalanceSegments(it) }.orEmpty()
    }
    val contentColor = color.takeOrElse { LocalContentColor.current }
    val animateKey = statusMessage ?: rawText.orEmpty()

    when (displayMode) {
        ProviderBalanceDisplayMode.Card -> {
            BalanceCard(
                modifier = modifier.fillMaxWidth(),
                segments = segments,
                statusMessage = statusMessage,
                stale = state.failed && rawText != null,
                loading = state.loading,
                allowRefresh = allowRefresh,
                animate = providerSetting.balanceOption.animate,
                animateKey = animateKey,
                onRefresh = { refresh++ },
                style = style,
                contentColor = contentColor,
            )
        }
        ProviderBalanceDisplayMode.Chips -> {
            BalanceChips(
                modifier = modifier,
                segments = segments,
                statusMessage = statusMessage,
                stale = state.failed && rawText != null,
                loading = state.loading,
                allowRefresh = allowRefresh,
                animate = providerSetting.balanceOption.animate,
                animateKey = animateKey,
                onRefresh = { refresh++ },
                style = style,
                contentColor = contentColor,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BalanceCard(
    modifier: Modifier,
    segments: List<ProviderBalanceSegment>,
    statusMessage: String?,
    stale: Boolean,
    loading: Boolean,
    allowRefresh: Boolean,
    animate: Boolean,
    animateKey: String,
    onRefresh: () -> Unit,
    style: TextStyle,
    contentColor: Color,
) {
    Surface(
        modifier = modifier,
        shape = CardCorner,
        color = modernTonal(),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.Wallet02,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = contentColor,
                )
                Text(
                    text = stringResource(R.string.provider_balance_card_title),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (allowRefresh) {
                    IconButton(
                        onClick = onRefresh,
                        enabled = !loading,
                        modifier = Modifier.size(32.dp),
                    ) {
                        if (loading) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                HugeIcons.Refresh03,
                                contentDescription = stringResource(R.string.provider_balance_refresh),
                            )
                        }
                    }
                }
            }

            @Composable
            fun Body() {
                when {
                    statusMessage != null -> {
                        Text(text = statusMessage, style = style, color = contentColor)
                    }
                    segments.isEmpty() -> {
                        Text(
                            text = stringResource(R.string.provider_balance_loading),
                            style = style,
                            color = contentColor,
                        )
                    }
                    else -> {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            segments.forEach { segment ->
                                BalanceTile(segment = segment)
                            }
                        }
                        if (stale) {
                            Text(
                                text = stringResource(R.string.provider_balance_stale_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (animate) {
                AnimatedContent(
                    targetState = animateKey,
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                    label = "providerBalanceCard",
                ) { _ -> Body() }
            } else {
                Body()
            }
        }
    }
}

@Composable
private fun BalanceTile(segment: ProviderBalanceSegment) {
    val label = balanceSegmentLabel(segment)
    val valueColor = balanceValueColor(segment)
    Surface(
        shape = TileCorner,
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (label != null) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            Text(
                text = segment.value,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = valueColor,
                maxLines = 3,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BalanceChips(
    modifier: Modifier,
    segments: List<ProviderBalanceSegment>,
    statusMessage: String?,
    stale: Boolean,
    loading: Boolean,
    allowRefresh: Boolean,
    animate: Boolean,
    animateKey: String,
    onRefresh: () -> Unit,
    style: TextStyle,
    contentColor: Color,
) {
    val chips = remember(segments) { compactProviderBalanceChips(segments) }

    @Composable
    fun ChipBody(bodyModifier: Modifier) {
        when {
            statusMessage != null -> {
                Text(
                    text = statusMessage,
                    style = style,
                    color = contentColor,
                    modifier = bodyModifier,
                )
            }
            chips.isEmpty() -> {
                Text(
                    text = stringResource(R.string.provider_balance_loading),
                    style = style,
                    color = contentColor,
                    modifier = bodyModifier,
                )
            }
            else -> {
                FlowRow(
                    modifier = bodyModifier,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    chips.forEach { segment ->
                        BalanceChip(segment = segment, style = style, contentColor = contentColor)
                    }
                    if (stale) {
                        Text(
                            text = stringResource(R.string.provider_balance_stale_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (animate) {
            AnimatedContent(
                targetState = animateKey,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "providerBalanceChips",
                modifier = Modifier.weight(1f, fill = false),
            ) { _ -> ChipBody(Modifier.fillMaxWidth()) }
        } else {
            ChipBody(Modifier.weight(1f, fill = false))
        }

        if (allowRefresh) {
            IconButton(onClick = onRefresh, enabled = !loading, modifier = Modifier.size(28.dp)) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        HugeIcons.Refresh03,
                        contentDescription = stringResource(R.string.provider_balance_refresh),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun BalanceChip(
    segment: ProviderBalanceSegment,
    style: TextStyle,
    contentColor: Color,
) {
    val label = when (segment.kind) {
        ProviderBalanceSegmentKind.TodayTotal -> stringResource(R.string.provider_balance_chip_today)
        ProviderBalanceSegmentKind.SubscriptionRemaining -> null
        ProviderBalanceSegmentKind.PoolType -> null
        ProviderBalanceSegmentKind.RefreshedAt ->
            stringResource(R.string.provider_balance_label_refreshed_at)
        ProviderBalanceSegmentKind.Generic -> segment.rawLabel
    }
    val valueColor = balanceValueColor(segment)
    Surface(
        shape = ChipCorner,
        color = modernTonal(),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (!label.isNullOrBlank()) {
                Text(
                    text = label,
                    style = style,
                    color = contentColor.copy(alpha = 0.75f),
                    maxLines = 2,
                )
            }
            Text(
                text = segment.value,
                style = style.copy(fontWeight = FontWeight.SemiBold),
                color = if (segment.kind == ProviderBalanceSegmentKind.SubscriptionRemaining) {
                    valueColor
                } else {
                    contentColor
                },
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun balanceSegmentLabel(segment: ProviderBalanceSegment): String? {
    return when (segment.kind) {
        ProviderBalanceSegmentKind.PoolType ->
            stringResource(R.string.provider_balance_label_pool_type)
        ProviderBalanceSegmentKind.SubscriptionRemaining ->
            stringResource(R.string.provider_balance_label_subscription_remaining)
        ProviderBalanceSegmentKind.RefreshedAt ->
            stringResource(R.string.provider_balance_label_refreshed_at)
        ProviderBalanceSegmentKind.TodayTotal ->
            stringResource(R.string.provider_balance_label_today_total)
        ProviderBalanceSegmentKind.Generic ->
            segment.rawLabel?.takeIf { it.isNotBlank() }
    }
}

@Composable
private fun balanceValueColor(segment: ProviderBalanceSegment): Color {
    return when (segment.kind) {
        ProviderBalanceSegmentKind.SubscriptionRemaining -> modernStatusGreen()
        else -> MaterialTheme.colorScheme.onSurface
    }
}
