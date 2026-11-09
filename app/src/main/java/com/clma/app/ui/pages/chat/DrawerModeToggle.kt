package com.clma.app.ui.pages.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import com.clma.app.ui.motion.Motion
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.clma.app.R
import com.composables.icons.lucide.Bot
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageSquare

private val ModeTrackShape = RoundedCornerShape(10.dp)
private val ModeThumbShape = RoundedCornerShape(8.dp)

/** 侧边栏「聊天 | Agent」分段切换（助手卡片样式，整行宽）。 */
@Composable
internal fun DrawerModeSegmented(
    agentMode: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val groupDesc = stringResource(R.string.chat_agent_mode_toggle)
    val stateDesc = stringResource(if (agentMode) R.string.chat_agent_mode_on else R.string.chat_agent_mode_off)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(ModeTrackShape)
            .background(drawerTonalColor())
            .padding(3.dp)
            .semantics {
                contentDescription = groupDesc
                stateDescription = stateDesc
            },
    ) {
        val segW = maxWidth / 2
        val thumbX by animateDpAsState(
            targetValue = if (agentMode) segW else 0.dp,
            animationSpec = Motion.springGentle(),
            label = "modeThumb",
        )
        Box(
            modifier = Modifier
                .offset(x = thumbX)
                .width(segW)
                .fillMaxHeight()
                .clip(ModeThumbShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
        )
        Row(modifier = Modifier.fillMaxSize().selectableGroup()) {
            ModeSegment(Lucide.MessageSquare, stringResource(R.string.chat_chat_mode), !agentMode, { onChange(false) }, Modifier.weight(1f))
            ModeSegment(Lucide.Bot, stringResource(R.string.chat_agent_mode), agentMode, { onChange(true) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ModeSegment(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = Motion.enter(Motion.FAST),
        label = "modeSegColor",
    )
    Row(
        modifier = modifier
            .fillMaxHeight()
            .clip(ModeThumbShape)
            .selectable(selected = selected, onClick = onClick, role = Role.Tab),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            color = color,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/** 助手导轨上的紧凑竖向两态切换：上「聊天」下「Agent」，只显示图标。 */
@Composable
internal fun DrawerModeRailToggle(
    agentMode: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val groupDesc = stringResource(R.string.chat_agent_mode_toggle)
    val stateDesc = stringResource(if (agentMode) R.string.chat_agent_mode_on else R.string.chat_agent_mode_off)
    val cell = 30.dp
    val thumbY by animateDpAsState(
        targetValue = if (agentMode) cell else 0.dp,
        animationSpec = Motion.springGentle(),
        label = "railModeThumb",
    )
    Box(
        modifier = modifier
            .width(cell + 6.dp)
            .height(cell * 2 + 6.dp)
            .clip(ModeTrackShape)
            .background(drawerTonalColor())
            .padding(3.dp)
            .semantics {
                contentDescription = groupDesc
                stateDescription = stateDesc
            },
    ) {
        Box(
            modifier = Modifier
                .offset(y = thumbY)
                .size(cell)
                .clip(ModeThumbShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
        )
        Column(modifier = Modifier.selectableGroup()) {
            RailModeCell(Lucide.MessageSquare, stringResource(R.string.chat_chat_mode), !agentMode, { onChange(false) }, cell)
            RailModeCell(Lucide.Bot, stringResource(R.string.chat_agent_mode), agentMode, { onChange(true) }, cell)
        }
    }
}

@Composable
private fun RailModeCell(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    cell: androidx.compose.ui.unit.Dp,
) {
    val color by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = Motion.enter(Motion.FAST),
        label = "railModeColor",
    )
    Box(
        modifier = Modifier
            .size(cell)
            .clip(ModeThumbShape)
            .background(Color.Transparent)
            .selectable(selected = selected, onClick = onClick, role = Role.Tab)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
    }
}
