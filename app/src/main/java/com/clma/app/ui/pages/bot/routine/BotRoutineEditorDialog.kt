// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), src/components/RoutinesPage.tsx, src/components/RoutineCalendarPage.tsx, src/components/routines/CronScheduleFields.tsx, src/components/RoutineScheduleNote.tsx, src/components/routines/ResultsDestination.tsx,
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0
// (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.ui.pages.bot.routine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.clma.app.R
import com.clma.app.data.botmode.BotRoutineForm
import com.clma.app.data.botmode.BotRoutineFormError
import com.clma.app.data.botmode.BotRoutineKind
import com.clma.app.data.botmode.botRoutineClock
import com.clma.app.data.botmode.validate
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale
import kotlin.uuid.Uuid

import com.clma.app.data.botmode.routine.RoutineInterval
import com.clma.app.data.botmode.routine.RoutineOptions
import com.clma.app.data.botmode.routine.RoutineOverlap
import com.clma.app.data.db.entity.ScheduledJobRunEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal data class RoutineEdit(
    val form: BotRoutineForm,
    val options: RoutineOptions,
    val endAtMs: Long?,
    val onceAtMs: Long?,
)

// ------------------------------------------------------------------ editor

private fun parseClock(text: String): Pair<Int, Int>? {
    val m = Regex("^\\s*(\\d{1,2})\\s*[:：.]\\s*(\\d{1,2})\\s*$").find(text) ?: return null
    val h = m.groupValues[1].toIntOrNull() ?: return null
    val min = m.groupValues[2].toIntOrNull() ?: return null
    return if (h in 0..23 && min in 0..59) h to min else null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BotRoutineEditorDialog(
    initial: BotRoutineForm,
    isNew: Boolean,
    initialOptions: RoutineOptions,
    endAtMs: Long?,
    onceAtMs: Long?,
    zone: ZoneId,
    history: List<ScheduledJobRunEntity>,
    onOpenConversation: (Uuid) -> Unit,
    onDismiss: () -> Unit,
    onSave: (RoutineEdit) -> Unit,
) {
    var form by remember { mutableStateOf(initial) }
    var clockText by remember { mutableStateOf(botRoutineClock(initial.hour, initial.minute)) }
    var intervalText by remember { mutableStateOf(initial.interval.toString()) }
    var options by remember { mutableStateOf(initialOptions) }
    var intervalDays by remember { mutableStateOf(initialOptions.interval?.weekdays.orEmpty()) }
    var windowStart by remember { mutableStateOf(initialOptions.interval?.startMinute?.takeIf { it != 0 }?.let {
        String.format(Locale.ROOT, "%02d:%02d", it / 60, it % 60)
    }.orEmpty()) }
    var windowEnd by remember { mutableStateOf(initialOptions.interval?.endMinute?.let {
        when (it) {
            1440 -> ""
            else -> String.format(Locale.ROOT, "%02d:%02d", it / 60, it % 60)
        }
    }.orEmpty()) }
    var endDate by remember { mutableStateOf(endAtMs?.let {
        Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toString()
    }.orEmpty()) }
    var onceDate by remember { mutableStateOf(onceAtMs?.let {
        Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toString()
    }.orEmpty()) }
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
    val error = if (form.kind == BotRoutineKind.EVERY_MINUTES && interval != null && interval in 1..1440) {
        candidate.copy(interval = 1).validate()
    } else candidate.validate()
    val clockBad = usesClock && clock == null
    val start = if (windowStart.isBlank()) 0 else parseClock(windowStart)?.let { it.first * 60 + it.second }
    val end = if (windowEnd.isBlank() || windowEnd == "24:00") 1440
        else parseClock(windowEnd)?.let { it.first * 60 + it.second }
    val until = runCatching { LocalDate.parse(endDate).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1 }.getOrNull()
    val once = if (clock == null) null else runCatching {
        LocalDate.parse(onceDate).atTime(clock.first, clock.second).atZone(zone).toInstant().toEpochMilli()
    }.getOrNull()
    val intervalMinutes = interval?.let { it * if (form.kind == BotRoutineKind.EVERY_HOURS) 60 else 1 }
    val intervalBad = usesInterval && (start == null || end == null || start >= end ||
        intervalMinutes != null && end - start < intervalMinutes ||
        endDate.isNotBlank() && (until == null || until < System.currentTimeMillis()))
    val onceBad = form.kind == BotRoutineKind.ONCE && onceDate.isNotBlank() &&
        (once == null || once < System.currentTimeMillis())
    val urlBad = options.changedUrl.isNotBlank() && options.changedUrl.trim().toHttpUrlOrNull() == null
    val canSave = error == null && !clockBad && !intervalBad && !onceBad && !urlBad

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
                            onClick = {
                                if (form.kind != kind && kind == BotRoutineKind.EVERY_HOURS && (interval == null || interval !in 1..23)) {
                                    intervalText = "1"
                                }
                                form = form.copy(kind = kind)
                            },
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
                                    if (form.kind == BotRoutineKind.ONCE && onceDate.isBlank()) R.string.bot_routine_field_time_once_hint
                                    else R.string.bot_routine_field_time_hint,
                                ),
                            )
                        },
                        singleLine = true,
                        isError = clockBad,
                        modifier = Modifier.width(200.dp),
                    )
                }
                if (form.kind == BotRoutineKind.ONCE) {
                    RoutineField(onceDate, { onceDate = it }, R.string.routine2_once_date, showErrors > 0 && onceBad)
                }
                if (usesInterval) {
                    OutlinedTextField(
                        value = intervalText,
                        onValueChange = { intervalText = it.filter(Char::isDigit).take(4) },
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
                                    else R.string.routine2_minutes_hint,
                                ),
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = error == BotRoutineFormError.INTERVAL,
                        modifier = Modifier.width(200.dp),
                    )
                    Text(stringResource(R.string.routine2_interval_days), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DayOfWeek.entries.forEach { day ->
                            FilterChip(
                                selected = day.value in intervalDays,
                                onClick = {
                                    intervalDays = if (day.value in intervalDays) intervalDays - day.value else intervalDays + day.value
                                },
                                label = { Text(day.getDisplayName(TextStyle.SHORT, locale)) },
                            )
                        }
                    }
                    RoutineField(windowStart, { windowStart = it }, R.string.routine2_window_start, showErrors > 0 && intervalBad)
                    RoutineField(windowEnd, { windowEnd = it }, R.string.routine2_window_end, showErrors > 0 && intervalBad)
                    RoutineField(endDate, { endDate = it }, R.string.routine2_end_date, showErrors > 0 && intervalBad)
                    if (showErrors > 0 && intervalBad) {
                        Text(stringResource(R.string.routine2_interval_error), color = MaterialTheme.colorScheme.error)
                    }
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
                RoutineField(options.condition, { options = options.copy(condition = it.take(1000)) }, R.string.routine2_only_if)
                RoutineField(options.changedUrl, { options = options.copy(changedUrl = it.take(2000)) }, R.string.routine2_changed_url,
                    showErrors > 0 && urlBad)
                Text(stringResource(R.string.routine2_overlap), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RoutineOverlap.entries.forEach { overlap ->
                        FilterChip(
                            selected = options.overlap == overlap,
                            onClick = { options = options.copy(overlap = overlap) },
                            label = { Text(stringResource(if (overlap == RoutineOverlap.SKIP) R.string.routine2_skip else R.string.routine2_queue)) },
                        )
                    }
                }
                Text(stringResource(R.string.routine2_destination), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(false, true).forEach { same ->
                        FilterChip(
                            selected = options.sameConversation == same,
                            onClick = { options = options.copy(sameConversation = same) },
                            label = { Text(stringResource(if (same) R.string.routine2_same_topic else R.string.routine2_new_topic)) },
                        )
                    }
                }
                Text(
                    stringResource(R.string.routine2_schedule_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!isNew) RoutineRunHistory(history, zone, onOpenConversation)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (canSave) {
                    val intervalSpec = if (usesInterval) {
                        RoutineInterval(requireNotNull(intervalMinutes), 0, intervalDays, requireNotNull(start), requireNotNull(end))
                    } else null
                    onSave(RoutineEdit(
                        candidate, options.copy(interval = intervalSpec, changedUrl = options.changedUrl.trim()),
                        until.takeIf { usesInterval }, once.takeIf { form.kind == BotRoutineKind.ONCE },
                    ))
                } else showErrors++
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
    BotRoutineKind.CUSTOM -> R.string.routine2_cron
}

@Composable
private fun RoutineField(value: String, onChange: (String) -> Unit, label: Int, invalid: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        isError = invalid,
        modifier = Modifier.fillMaxWidth(),
    )
}
