package com.clma.app.ui.pages.bot

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clma.app.R
import com.clma.app.ui.components.ScheduledTaskReliabilityCard
import com.clma.app.data.botmode.BotRoutineForm
import com.clma.app.data.botmode.BotRoutineFormError
import com.clma.app.data.botmode.BotRoutineKind
import com.clma.app.data.botmode.BotRoutineRowSummary
import com.clma.app.data.botmode.BotRoutineSchedule
import com.clma.app.data.botmode.ParkedRoutineRun
import com.clma.app.data.botmode.botRoutineClock
import com.clma.app.data.botmode.botRoutineFormFrom
import com.clma.app.data.botmode.parseBotRoutineSchedule
import com.clma.app.data.botmode.validate
import com.clma.app.data.db.entity.ScheduledJobEntity
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.uuid.Uuid

// ------------------------------------------------------------------ labels

/** Localised one-line schedule, e.g. "工作日 9:00" / "Weekdays 9:00". */
@Composable
fun botRoutineScheduleText(schedule: BotRoutineSchedule): String {
    val locale = Locale.getDefault()
    return when (schedule) {
        is BotRoutineSchedule.Daily ->
            stringResource(R.string.bot_routine_sched_daily, botRoutineClock(schedule.hour, schedule.minute))
        is BotRoutineSchedule.Weekdays ->
            stringResource(R.string.bot_routine_sched_weekdays, botRoutineClock(schedule.hour, schedule.minute))
        is BotRoutineSchedule.Weekly -> stringResource(
            R.string.bot_routine_sched_weekly,
            schedule.days.joinToString(stringResource(R.string.bot_routine_day_separator)) {
                it.getDisplayName(TextStyle.SHORT, locale)
            },
            botRoutineClock(schedule.hour, schedule.minute),
        )
        is BotRoutineSchedule.EveryMinutes -> stringResource(R.string.bot_routine_sched_every_minutes, schedule.minutes)
        is BotRoutineSchedule.EveryHours -> stringResource(R.string.bot_routine_sched_every_hours, schedule.hours)
        is BotRoutineSchedule.Once -> {
            val at = Instant.ofEpochMilli(schedule.atMs).atZone(ZoneId.systemDefault())
            stringResource(
                R.string.bot_routine_sched_once,
                "${at.monthValue}/${at.dayOfMonth} ${botRoutineClock(at.hour, at.minute)}",
            )
        }
        is BotRoutineSchedule.Custom -> stringResource(R.string.bot_routine_sched_custom, schedule.expression)
    }
}

/** The bot list's routine line: "自动任务 · 工作日 9:00" (+N when the bot has more). */
@Composable
fun botRoutineRowText(summary: BotRoutineRowSummary): String {
    val schedule = botRoutineScheduleText(parseBotRoutineSchedule(summary.next))
    return if (summary.others > 0) {
        stringResource(R.string.bot_routine_row_more, schedule, summary.others)
    } else {
        stringResource(R.string.bot_routine_row, schedule)
    }
}

// ------------------------------------------------------------------ info page group

/**
 * "自动任务" group on the Bot info page: this bot's scheduled jobs with pause / run now / edit /
 * delete, runs waiting on the user's approval, and background reliability guidance.
 */
@Composable
fun BotRoutinesGroup(
    routines: List<ScheduledJobEntity>,
    parkedRuns: List<ParkedRoutineRun>,
    onSave: (BotRoutineForm, String?) -> Unit,
    onSetEnabled: (String, Boolean) -> Unit,
    onRunNow: (String, (Boolean) -> Unit) -> Unit,
    onDelete: (String) -> Unit,
    onOpenConversation: (Uuid) -> Unit,
) {
    var editing by remember { mutableStateOf<ScheduledJobEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<ScheduledJobEntity?>(null) }

    val enabledCount = routines.count { it.enabled }
    BotCollapsibleGroup(
        key = "routines",
        title = stringResource(R.string.bot_routine_group_title),
        summary = stringResource(R.string.bot_routine_group_summary, routines.size, enabledCount),
    ) {
        Text(
            stringResource(R.string.bot_routine_group_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ScheduledTaskReliabilityCard()
        parkedRuns.filterNot { it.resuming }.forEach { run ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth(),
                onClick = { runCatching { Uuid.parse(run.conversationId) }.getOrNull()?.let(onOpenConversation) },
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        stringResource(R.string.bot_routine_waiting_title, run.jobName),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        stringResource(R.string.bot_routine_waiting_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
        if (routines.isEmpty()) {
            Text(
                stringResource(R.string.bot_routine_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        routines.forEach { job ->
            BotRoutineRow(
                job = job,
                onToggle = { onSetEnabled(job.id, it) },
                onRunNow = {
                    onRunNow(job.id) { fired ->
                        Toast.makeText(
                            context,
                            context.getString(if (fired) R.string.bot_routine_run_now_started else R.string.bot_routine_run_now_paused),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
                onEdit = { editing = job },
                onDelete = { confirmDelete = job },
            )
        }
        FilledTonalButton(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.bot_routine_add))
        }
    }

    if (creating) {
        BotRoutineEditorDialog(
            initial = BotRoutineForm(),
            isNew = true,
            onDismiss = { creating = false },
            onSave = { form -> creating = false; onSave(form, null) },
        )
    }
    editing?.let { job ->
        BotRoutineEditorDialog(
            initial = remember(job.id) { botRoutineFormFrom(job) },
            isNew = false,
            onDismiss = { editing = null },
            onSave = { form -> editing = null; onSave(form, job.id) },
        )
    }
    confirmDelete?.let { job ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.bot_routine_delete_title)) },
            text = { Text(stringResource(R.string.bot_routine_delete_confirm, job.name)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = null; onDelete(job.id) }) {
                    Text(stringResource(R.string.bot_home_menu_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.bot_home_cancel)) } },
        )
    }
}

@Composable
private fun BotRoutineRow(
    job: ScheduledJobEntity,
    onToggle: (Boolean) -> Unit,
    onRunNow: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    // Direct-mode jobs (fixed tool actions, made by the model) are listed but not editable here.
    val editable = job.mode == "llm"
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(job.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        botRoutineScheduleText(parseBotRoutineSchedule(job)) + nextRunSuffix(job),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(checked = job.enabled, onCheckedChange = onToggle)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                TextButton(onClick = onRunNow, enabled = job.enabled) { Text(stringResource(R.string.bot_routine_run_now)) }
                if (editable) {
                    TextButton(onClick = onEdit) { Text(stringResource(R.string.bot_routine_edit)) }
                }
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.bot_home_menu_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun nextRunSuffix(job: ScheduledJobEntity): String {
    if (!job.enabled) return " · " + stringResource(R.string.bot_routine_paused)
    val next = job.nextRunAtMs ?: return ""
    val at = Instant.ofEpochMilli(next).atZone(ZoneId.systemDefault())
    return " · " + stringResource(
        R.string.bot_routine_next_at,
        "${at.monthValue}/${at.dayOfMonth} ${botRoutineClock(at.hour, at.minute)}",
    )
}

// ------------------------------------------------------------------ editor

private fun parseClock(text: String): Pair<Int, Int>? {
    val m = Regex("^\\s*(\\d{1,2})\\s*[:：.]\\s*(\\d{1,2})\\s*$").find(text) ?: return null
    val h = m.groupValues[1].toIntOrNull() ?: return null
    val min = m.groupValues[2].toIntOrNull() ?: return null
    return if (h in 0..23 && min in 0..59) h to min else null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BotRoutineEditorDialog(
    initial: BotRoutineForm,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (BotRoutineForm) -> Unit,
) {
    var form by remember { mutableStateOf(initial) }
    var clockText by remember { mutableStateOf(botRoutineClock(initial.hour, initial.minute)) }
    var intervalText by remember { mutableStateOf(initial.interval.toString()) }
    var showErrors by remember { mutableIntStateOf(0) }
    val locale = Locale.getDefault()

    val clock = parseClock(clockText)
    val interval = intervalText.trim().toIntOrNull()
    val usesClock = form.kind in setOf(BotRoutineKind.DAILY, BotRoutineKind.WEEKDAYS, BotRoutineKind.WEEKLY, BotRoutineKind.ONCE)
    val usesInterval = form.kind == BotRoutineKind.EVERY_MINUTES || form.kind == BotRoutineKind.EVERY_HOURS
    val candidate = form.copy(
        hour = clock?.first ?: form.hour,
        minute = clock?.second ?: form.minute,
        interval = interval ?: 0,
    )
    val error: BotRoutineFormError? = candidate.validate()
    val clockBad = usesClock && clock == null
    val canSave = error == null && !clockBad

    // When switching between minutes and hours, keep the interval inside the new range.
    LaunchedEffect(form.kind) {
        if (form.kind == BotRoutineKind.EVERY_HOURS && (interval == null || interval !in 1..23)) intervalText = "1"
        if (form.kind == BotRoutineKind.EVERY_MINUTES && (interval == null || interval !in 1..59)) intervalText = "30"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isNew) R.string.bot_routine_new_title else R.string.bot_routine_edit_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = form.name,
                    onValueChange = { form = form.copy(name = it.take(BotRoutineForm.NAME_MAX)) },
                    label = { Text(stringResource(R.string.bot_routine_field_name)) },
                    singleLine = true,
                    isError = showErrors > 0 && error == BotRoutineFormError.NAME,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = form.prompt,
                    onValueChange = { form = form.copy(prompt = it.take(BotRoutineForm.PROMPT_MAX)) },
                    label = { Text(stringResource(R.string.bot_routine_field_prompt)) },
                    placeholder = { Text(stringResource(R.string.bot_routine_field_prompt_hint)) },
                    minLines = 3,
                    maxLines = 8,
                    isError = showErrors > 0 && error == BotRoutineFormError.PROMPT,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.bot_routine_field_when), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BotRoutineKind.entries.forEach { kind ->
                        FilterChip(
                            selected = form.kind == kind,
                            onClick = { form = form.copy(kind = kind) },
                            label = { Text(stringResource(kindLabel(kind))) },
                        )
                    }
                }
                if (form.kind == BotRoutineKind.WEEKLY) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DayOfWeek.values().forEach { day ->
                            FilterChip(
                                selected = day in form.days,
                                onClick = {
                                    form = form.copy(days = if (day in form.days) form.days - day else form.days + day)
                                },
                                label = { Text(day.getDisplayName(TextStyle.SHORT, locale)) },
                            )
                        }
                    }
                    if (error == BotRoutineFormError.DAYS) {
                        Text(
                            stringResource(R.string.bot_routine_error_days),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (usesClock) {
                    OutlinedTextField(
                        value = clockText,
                        onValueChange = { clockText = it.take(5) },
                        label = { Text(stringResource(R.string.bot_routine_field_time)) },
                        supportingText = {
                            Text(
                                stringResource(
                                    if (form.kind == BotRoutineKind.ONCE) R.string.bot_routine_field_time_once_hint
                                    else R.string.bot_routine_field_time_hint,
                                ),
                            )
                        },
                        singleLine = true,
                        isError = clockBad,
                        modifier = Modifier.width(200.dp),
                    )
                }
                if (usesInterval) {
                    OutlinedTextField(
                        value = intervalText,
                        onValueChange = { intervalText = it.filter(Char::isDigit).take(2) },
                        label = {
                            Text(
                                stringResource(
                                    if (form.kind == BotRoutineKind.EVERY_HOURS) R.string.bot_routine_field_hours
                                    else R.string.bot_routine_field_minutes,
                                ),
                            )
                        },
                        supportingText = {
                            Text(
                                stringResource(
                                    if (form.kind == BotRoutineKind.EVERY_HOURS) R.string.bot_routine_field_hours_hint
                                    else R.string.bot_routine_field_minutes_hint,
                                ),
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = error == BotRoutineFormError.INTERVAL,
                        modifier = Modifier.width(200.dp),
                    )
                }
                if (form.kind == BotRoutineKind.CUSTOM) {
                    OutlinedTextField(
                        value = form.cron,
                        onValueChange = { form = form.copy(cron = it.take(120)) },
                        label = { Text(stringResource(R.string.bot_routine_field_cron)) },
                        supportingText = { Text(stringResource(R.string.bot_routine_field_cron_hint)) },
                        singleLine = true,
                        isError = form.cron.isNotBlank() && error == BotRoutineFormError.CRON,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    stringResource(R.string.bot_routine_editor_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (canSave) onSave(candidate) else showErrors++
            }) { Text(stringResource(R.string.bot_mode_channels_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.bot_home_cancel)) } },
    )
}

private fun kindLabel(kind: BotRoutineKind): Int = when (kind) {
    BotRoutineKind.DAILY -> R.string.bot_routine_kind_daily
    BotRoutineKind.WEEKDAYS -> R.string.bot_routine_kind_weekdays
    BotRoutineKind.WEEKLY -> R.string.bot_routine_kind_weekly
    BotRoutineKind.EVERY_MINUTES -> R.string.bot_routine_kind_every_minutes
    BotRoutineKind.EVERY_HOURS -> R.string.bot_routine_kind_every_hours
    BotRoutineKind.ONCE -> R.string.bot_routine_kind_once
    BotRoutineKind.CUSTOM -> R.string.bot_routine_kind_custom
}
