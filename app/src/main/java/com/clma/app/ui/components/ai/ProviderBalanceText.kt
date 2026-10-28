package com.clma.app.ui.components.ai

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clma.ai.provider.ProviderManager
import com.clma.ai.provider.ProviderSetting
import com.clma.app.R
import com.clma.app.utils.SimpleCache
import com.clma.app.utils.toDp
import kotlinx.coroutines.CancellationException
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.MoneyBag02
import me.rerere.hugeicons.stroke.Refresh03
import org.koin.compose.koinInject
import java.util.concurrent.TimeUnit

private val cache = SimpleCache.builder<String, String>()
    .expireAfterWrite(2, TimeUnit.MINUTES)
    .build()

private data class BalanceState(
    val text: String? = null,
    val loading: Boolean = true,
    val failed: Boolean = false,
)

@Composable
fun ProviderBalanceText(
    providerSetting: ProviderSetting,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    allowRefresh: Boolean = false,
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
    val text = when {
        state.failed && state.text != null -> stringResource(R.string.provider_balance_stale, state.text!!)
        state.failed -> stringResource(R.string.provider_balance_failed)
        state.text != null -> state.text!!
        else -> stringResource(R.string.provider_balance_loading)
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = HugeIcons.MoneyBag02,
            contentDescription = null,
            modifier = Modifier.size(style.fontSize.toDp()),
            tint = color.takeOrElse { LocalContentColor.current },
        )
        if (providerSetting.balanceOption.animate) {
            AnimatedContent(
                targetState = text,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "providerBalance",
                modifier = Modifier.weight(1f, fill = false),
            ) { displayed ->
                Text(displayed, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, color = color)
            }
        } else {
            Text(text, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, color = color,
                modifier = Modifier.weight(1f, fill = false))
        }
        if (allowRefresh) {
            IconButton(onClick = { refresh++ }, enabled = !state.loading) {
                if (state.loading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(HugeIcons.Refresh03, contentDescription = stringResource(R.string.provider_balance_refresh))
                }
            }
        }
    }
}
