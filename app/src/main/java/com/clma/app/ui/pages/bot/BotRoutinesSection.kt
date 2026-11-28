package com.clma.app.ui.pages.bot

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clma.app.R
import com.clma.app.data.botmode.BotRoutineForm
import com.clma.app.data.botmode.BotRoutineKind
import com.clma.app.data.botmode.routine.CheckBack
import com.clma.app.data.botmode.routine.CheckBackDelivery
import com.clma.app.data.botmode.routine.RoutineOptions
import com.clma.app.data.botmode.routine.RoutineStore
import com.clma.app.data.botmode.routine.routineZone
import com.clma.app.data.repository.ScheduledJobRunRepository
import com.clma.app.ui.pages.bot.routine.BotRoutineEditorDialog
import com.clma.app.ui.pages.bot.routine.RoutineEdit
import com.clma.app.ui.pages.bot.routine.PendingCheckBacks
import com.clma.app.ui.pages.bot.routine.RoutineBackupModel
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.clma.app.data.botmode.settings.BotModeSettings
import com.clma.app.data.botmode.settings.BotModeSettingsStore
import com.clma.app.ui.components.ScheduledTaskReliabilityCard
import com.clma.app.data.botmode.BotRoutineRowSummary
import com.clma.app.data.botmode.BotRoutineSchedule
import com.clma.app.data.botmode.ParkedRoutineRun
import com.clma.app.data.botmode.botRoutineClock
import com.clma.app.data.botmode.botRoutineFormFrom
import com.clma.app.data.botmode.parseBotRoutineSchedule
import com.clma.app.data.db.entity.ScheduledJobEntity
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.uuid.Uuid
import org.koin.compose.koinInject

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
    val store = koinInject<RoutineStore>()
    val snapshot by store.snapshots.collectAsStateWithLifecycle()
    LaunchedEffect(store) { store.read() }
    val interval = snapshot.routines[summary.next.id]?.options?.interval
    val schedule = interval?.let { stringResource(R.string.bot_routine_sched_every_minutes, it.minutes) }
        ?: botRoutineScheduleText(parseBotRoutineSchedule(summary.next))
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
internal fun BotRoutinesGroup(
    routines: List<ScheduledJobEntity>,
    parkedRuns: List<ParkedRoutineRun>,
    botId: Uuid,
    timezone: String?,
    models: List<Pair<String, String>>,
    onSave: (RoutineEdit, String?) -> Unit,
    onSetEnabled: (String, Boolean) -> Unit,
    onRunNow: (String, (Boolean) -> Unit) -> Unit,
    onDelete: (String) -> Unit,
    onOpenConversation: (Uuid) -> Unit,
) {
    val context = LocalContext.current
    val store = koinInject<RoutineStore>()
    val historyRepo = koinInject<ScheduledJobRunRepository>()
    val scope = rememberCoroutineScope()
    val snapshot by store.snapshots.collectAsStateWithLifecycle()
    LaunchedEffect(store) { store.read() }
    val zone = routineZone(timezone)
    val botSettingsStore = koinInject<BotModeSettingsStore>()
    val botSettings by botSettingsStore.settings.collectAsStateWithLifecycle(initialValue = BotModeSettings())
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
        if (botSettings.showRoutineReliability) {
            ScheduledTaskReliabilityCard()
        }
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
        RoutineBackupModel(models, snapshot.backupModels[botId.toString()]) { model ->
            scope.launch {
                store.update {
                    val assistantId = botId.toString()
                    val updated = if (model == null) it.backupModels - assistantId
                        else it.backupModels + (assistantId to model)
                    it.copy(backupModels = updated)
                }
            }
        }
        PendingCheckBacks(
            snapshot.checkBacks.values.filter { it.assistantId == botId.toString() },
            zone, onOpenConversation,
            onCancel = { item: CheckBack ->
                scope.launch { CheckBackDelivery.cancel(context, store, item.id, item.conversationId) }
            },
        )
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
                intervalMinutes = snapshot.routines[job.id]?.options?.interval?.minutes,
                zone = zone,
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
            initialOptions = RoutineOptions(),
            endAtMs = null,
            onceAtMs = null,
            zone = zone,
            history = emptyList(),
            onOpenConversation = onOpenConversation,
            onDismiss = { creating = false },
            onSave = { form -> creating = false; onSave(form, null) },
        )
    }
    editing?.let { job ->
        val options = snapshot.routines[job.id]?.options ?: RoutineOptions()
        val history by remember(job.id) { historyRepo.observeRecent(job.id, 30) }
            .collectAsStateWithLifecycle(initialValue = emptyList())
        BotRoutineEditorDialog(
            initial = remember(job.id, options.interval, zone) {
                val form = botRoutineFormFrom(job, routineZone(job.timezone ?: timezone))
                options.interval?.let { form.copy(kind = BotRoutineKind.EVERY_MINUTES, interval = it.minutes) } ?: form
            },
            isNew = false,
            initialOptions = options,
            endAtMs = job.endAtUnixMs,
            onceAtMs = job.atUnixMs?.takeIf { it > System.currentTimeMillis() },
            zone = routineZone(job.timezone ?: timezone),
            history = history,
            onOpenConversation = { editing = null; onOpenConversation(it) },
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
    intervalMinutes: Int?,
    zone: ZoneId,
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
                        (intervalMinutes?.let { stringResource(R.string.bot_routine_sched_every_minutes, it) }
                            ?: botRoutineScheduleText(parseBotRoutineSchedule(job))) + nextRunSuffix(job, zone),
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
private fun nextRunSuffix(job: ScheduledJobEntity, zone: ZoneId): String {
    if (!job.enabled) return " · " + stringResource(R.string.bot_routine_paused)
    val next = job.nextRunAtMs ?: return ""
    val at = Instant.ofEpochMilli(next).atZone(zone)
    return " · " + stringResource(
        R.string.bot_routine_next_at,
        "${at.monthValue}/${at.dayOfMonth} ${botRoutineClock(at.hour, at.minute)}",
    )
}
