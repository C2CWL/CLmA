// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), src/components/Reactions.tsx, src/components/widget/WidgetMessage.tsx, src/components/widget/WidgetControls.tsx
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0 (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.ui.pages.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.clma.ai.core.MessageRole
import com.clma.ai.ui.UIMessage
import com.clma.app.R
import com.clma.app.data.botmode.BotChatWidget
import com.clma.app.data.botmode.botChatWidget
import com.clma.app.data.botmode.botReactionEmoji
import com.clma.app.data.botmode.widgetDate
import com.clma.app.data.botmode.widgetReplyId
import com.clma.app.data.model.Conversation
import com.clma.app.ui.context.LocalSettings
import com.clma.app.ui.theme.LocalChatFontFamily
import com.clma.app.ui.theme.rememberChatFontFamily
import com.clma.app.ui.theme.withFontFamily
import java.time.Instant
import java.time.ZoneOffset
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.abs
import kotlin.uuid.Uuid

private val reactionLabels = listOf(
    R.string.bot_reaction_like, R.string.bot_reaction_dislike, R.string.bot_reaction_love,
    R.string.bot_reaction_laugh, R.string.bot_reaction_celebrate, R.string.bot_reaction_looking,
)

@Composable
internal fun BotChatMessageContent(
    message: UIMessage,
    conversation: Conversation,
    loading: Boolean,
    onReaction: (Uuid, String) -> Unit,
    onWidgetResponse: (Uuid, String, String?, String?) -> Boolean,
) {
    if (message.role != MessageRole.ASSISTANT || message.isSynthetic || message.parts.isEmpty()) return
    val messages = conversation.currentMessages
    val font = LocalChatFontFamily.current ?: rememberChatFontFamily(LocalSettings.current.displaySetting)
    MaterialTheme(typography = MaterialTheme.typography.withFontFamily(font)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            message.getTools().forEach { tool ->
                val widget = remember(tool) { tool.botChatWidget() } ?: return@forEach
                key(message.id, tool.toolCallId) {
                    val replyId = remember(conversation.id, message.id, tool.toolCallId) {
                        widgetReplyId(conversation.id, message.id, tool.toolCallId)
                    }
                    val sent = messages.any { it.id == replyId }
                    val enabled = !loading && !sent &&
                        messages.none { row -> row.getTools().any { it.isPending } }
                    BotWidgetCard(widget, enabled, sent) { buttonId, date ->
                        onWidgetResponse(message.id, tool.toolCallId, buttonId, date)
                    }
                }
            }
            if (!loading || message.botReaction != null) {
                val groupLabel = stringResource(R.string.bot_reactions)
                Row(
                    Modifier.horizontalScroll(rememberScrollState())
                        .semantics { contentDescription = groupLabel },
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    botReactionEmoji.forEachIndexed { index, emoji ->
                        val label = stringResource(reactionLabels[index])
                        FilterChip(
                            selected = message.botReaction == emoji,
                            enabled = !loading,
                            onClick = { onReaction(message.id, emoji) },
                            label = { Text(emoji, Modifier.semantics { contentDescription = label }) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BotWidgetCard(
    widget: BotChatWidget,
    enabled: Boolean,
    sent: Boolean,
    onSubmit: (String?, String?) -> Boolean,
) {
    var sendFailed by remember { mutableStateOf(false) }
    val submit: (String?, String?) -> Unit = { buttonId, date ->
        sendFailed = !onSubmit(buttonId, date)
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(widget.title, fontWeight = FontWeight.SemiBold)
            widget.caption?.let { Text(it) }
            widget.table?.let { table ->
                Column(Modifier.horizontalScroll(rememberScrollState())) {
                    (listOf(table.columns) + table.rows).forEachIndexed { index, cells ->
                        Row {
                            cells.forEach { cell ->
                                Text(
                                    text = cell,
                                    modifier = Modifier.width(128.dp).padding(4.dp),
                                    fontWeight = if (index == 0) FontWeight.SemiBold else null,
                                )
                            }
                        }
                    }
                }
            }
            widget.chart?.let { chart -> BotWidgetChart(chart) }
            widget.buttons.forEach { button ->
                TextButton(enabled = enabled, onClick = { submit(button.id, null) }) {
                    Text(button.label)
                }
            }
            widget.datePicker?.let { picker ->
                BotWidgetDateButton(picker, enabled) { submit(null, it) }
            }
            if (sent) {
                Text(stringResource(R.string.bot_widget_sent))
            } else if (sendFailed) {
                Text(stringResource(R.string.bot_widget_send_failed), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun BotWidgetChart(chart: BotChatWidget.Chart) {
    Text(chart.label)
    val scale = chart.points.maxOf { abs(it.value) }.takeIf { it > 0 } ?: 1.0
    val signed = chart.points.any { it.value < 0 }
    val colors = MaterialTheme.colorScheme
    chart.points.forEach { point ->
        Text(stringResource(R.string.bot_widget_chart_point, point.label, point.value.toString()))
        val fraction = (abs(point.value) / scale).toFloat()
        Row(Modifier.fillMaxWidth().height(6.dp)) {
            if (signed) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    if (point.value < 0) {
                        Box(Modifier.fillMaxWidth(fraction).height(6.dp).background(colors.tertiary))
                    }
                }
            }
            Box(Modifier.weight(1f)) {
                if (point.value > 0) {
                    Box(Modifier.fillMaxWidth(fraction).height(6.dp).background(colors.primary))
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BotWidgetDateButton(
    picker: BotChatWidget.DatePicker,
    enabled: Boolean,
    onSubmit: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    TextButton(enabled = enabled, onClick = { open = true }) { Text(picker.label) }
    if (open) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = picker.defaultDate?.let(::widgetDate)
                ?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
            yearRange = 1900..2100,
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(enabled = enabled && state.selectedDateMillis != null, onClick = {
                    val millis = state.selectedDateMillis ?: return@TextButton
                    open = false
                    onSubmit(Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC).toLocalDate().toString())
                }) { Text(stringResource(R.string.bot_widget_choose)) }
            },
            dismissButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        ) { DatePicker(state) }
    }
}
