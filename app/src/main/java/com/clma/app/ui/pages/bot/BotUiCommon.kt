package com.clma.app.ui.pages.bot

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.clma.app.R
import com.clma.app.data.botmode.BotTimeLabelKind
import com.clma.app.data.botmode.botTimeLabel
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Lucide
import java.time.Instant
import java.time.format.TextStyle
import java.util.Locale

/** "07:05" / "昨天" / "周六" / a date, the same labels as the bot list. */
@Composable
fun botTimeText(at: Instant?): String {
    val locale = Locale.getDefault()
    val now = remember { Instant.now() }
    val label = remember(at, locale) {
        botTimeLabel(at, now, weekdayName = { it.getDisplayName(TextStyle.SHORT, locale) })
    }
    return when (label.kind) {
        BotTimeLabelKind.NONE -> ""
        BotTimeLabelKind.YESTERDAY -> stringResource(R.string.bot_home_time_yesterday)
        else -> label.text
    }
}

/**
 * A rounded group on the Bot info page whose body folds away under its title. The open state
 * survives rotation and coming back from a sub-page.
 */
@Composable
fun BotCollapsibleGroup(
    key: String,
    title: String,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = true,
    summary: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(key) { mutableStateOf(initiallyExpanded) }
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (!expanded && !summary.isNullOrBlank()) {
                        Text(
                            summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                Icon(
                    Lucide.ChevronDown,
                    contentDescription = stringResource(
                        if (expanded) R.string.bot_info_collapse else R.string.bot_info_expand,
                    ),
                    modifier = Modifier.size(18.dp).rotate(if (expanded) 180f else 0f),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = content,
                )
            }
        }
    }
}
