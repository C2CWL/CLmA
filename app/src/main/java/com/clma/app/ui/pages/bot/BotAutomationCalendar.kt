// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), src/components/RoutineCalendarPage.tsx, src/components/RoutinesPage.tsx, src/components/automations/AutomationsCalendar.tsx,
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0
// (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.ui.pages.bot

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clma.app.R
import com.clma.app.Screen
import com.clma.app.data.botmode.botAssistants
import com.clma.app.data.botmode.calendarNextRun
import com.clma.app.data.botmode.calendarSlots
import com.clma.app.data.botmode.parseBotRoutineSchedule
import com.clma.app.data.botmode.routine.RoutineStore
import com.clma.app.data.botmode.routine.routineZone
import com.clma.app.data.datastore.Settings
import com.clma.app.data.db.entity.ScheduledJobEntity
import com.clma.app.data.db.entity.ScheduledJobRunEntity
import com.clma.app.data.model.Assistant
import com.clma.app.data.repository.ConversationRepository
import com.clma.app.data.repository.ScheduledJobRepository
import com.clma.app.data.repository.ScheduledJobRunRepository
import com.clma.app.ui.context.LocalNavController
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import java.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01

private const val RUN_LIMIT = 100

private data class AgendaEntry(
    val key: String,
    val at: Long,
    val assistantId: String,
    val jobId: String? = null,
    val conversationId: String? = null,
    val run: ScheduledJobRunEntity? = null,
    val note: String? = null,
    val quotaRetry: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BotAutomationCalendar(settings: Settings, modifier: Modifier = Modifier) {
    if (!settings.displaySetting.botModeEnabled) return
    val nav = LocalNavController.current
    val scope = rememberCoroutineScope()
    val conversationRepo = koinInject<ConversationRepository>()
    val jobsRepo = koinInject<ScheduledJobRepository>()
    val runsRepo = koinInject<ScheduledJobRunRepository>()
    val allJobs by remember(jobsRepo) { jobsRepo.observeAll() }.collectAsStateWithLifecycle(emptyList())
    val routineStore = koinInject<RoutineStore>()
    val snapshot by routineStore.snapshots.collectAsStateWithLifecycle()
    val checkBackReadFailed by produceState(false, routineStore) {
        try {
            routineStore.read()
        } catch (_: IOException) {
            value = true
        } catch (_: SerializationException) {
            value = true
        }
    }
    val nowMs by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(30_000)
        }
    }
    val bots = settings.botAssistants()
    var ownerFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = bots.firstOrNull { it.id.toString() == ownerFilter }
    val owners = (selected?.let { listOf(it) } ?: bots).associateBy { it.id.toString() }
    val jobs = allJobs.filter { it.assistantId in owners }.associateBy { it.id }
    var calendar by rememberSaveable { mutableStateOf(true) }
    var epochDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var choosingDate by rememberSaveable { mutableStateOf(false) }
    var detailsId by rememberSaveable { mutableStateOf<String?>(null) }

    val zone = routineZone(settings.customTimezone)
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val date = epochDay?.let(LocalDate::ofEpochDay) ?: today
    val fromMs = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val untilMs = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val ownerIds = owners.keys.toList()
    val runsFlow = remember(runsRepo, ownerIds, calendar, fromMs, untilMs) {
        runsRepo.observeBotRuns(
            ownerIds,
            if (calendar) fromMs else 0L,
            if (calendar) untilMs else Long.MAX_VALUE,
            RUN_LIMIT + 1,
        )
    }
    val loadedRuns by runsFlow.collectAsStateWithLifecycle(emptyList())
    val runs = loadedRuns.filter {
        it.jobId in jobs && (!calendar || (it.startedAtMs >= fromMs && it.startedAtMs < untilMs))
    }
    val history = runs.take(RUN_LIMIT).mapNotNull { run ->
        jobs[run.jobId]?.let { job ->
            AgendaEntry("run:${run.id}", run.startedAtMs, job.assistantId, job.id, run.conversationId, run)
        }
    }
    val checkBacks = snapshot.checkBacks.values.filter { it.assistantId in owners }.map {
        AgendaEntry("check:${it.id}", it.atMs, it.assistantId, conversationId = it.conversationId, note = it.note)
    }
    val forecast = produceState<List<AgendaEntry>?>(null, jobs, snapshot.routines, calendar, date, today, zone) {
        value = null
        value = if (calendar && date >= today) withContext(Dispatchers.Default) {
            jobs.values.flatMap { job ->
                val interval = snapshot.routines[job.id]?.options?.interval
                calendarSlots(job, fromMs, untilMs, zone, interval).map {
                    AgendaEntry("slot:${job.id}:$it", it, job.assistantId, job.id)
                }
            }
        } else emptyList()
    }.value
    val recordedSlots = history.mapNotNull { entry -> entry.run?.let { it.jobId to it.scheduledAtMs } }.toSet()
    val retries = jobs.values.mapNotNull { job ->
        val quota = snapshot.routines[job.id]?.quota?.takeIf { job.enabled } ?: return@mapNotNull null
        AgendaEntry("retry:${quota.runId}", quota.atMs, job.assistantId, job.id, quotaRetry = true)
    }
    val timeline = if (calendar) {
        (history + (checkBacks + retries).filter { it.at in fromMs until untilMs } + forecast.orEmpty().filter {
            it.jobId in jobs && it.at in fromMs until untilMs && (it.jobId to it.at) !in recordedSlots
        }).sortedBy { it.at }
    } else emptyList()
    val openConversation: (String, String, String?) -> Unit = { botId, id, jobId ->
        scope.launch {
            val parsed = runCatching { Uuid.parse(id) }.getOrNull() ?: return@launch
            if (conversationRepo.getAssistantIdOfConversation(parsed)?.toString() == botId) {
                nav.navigate(Screen.BotChat(botId, id))
            } else if (jobId != null) {
                detailsId = jobId
            }
        }
    }

    Column(modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(calendar, { calendar = true }, { Text(stringResource(R.string.bot_calendar_day)) })
            FilterChip(!calendar, { calendar = false }, { Text(stringResource(R.string.bot_calendar_overview)) })
        }
        BotCalendarFilter(bots, selected) { ownerFilter = it }
        if (calendar) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { epochDay = date.minusDays(1).toEpochDay() }) {
                    Icon(HugeIcons.ArrowLeft01, stringResource(R.string.bot_calendar_previous))
                }
                TextButton(onClick = { choosingDate = true }, modifier = Modifier.weight(1f)) {
                    Text(date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)))
                }
                TextButton(onClick = { epochDay = null }) { Text(stringResource(R.string.bot_calendar_today)) }
                IconButton(onClick = { epochDay = date.plusDays(1).toEpochDay() }) {
                    Icon(Lucide.ChevronRight, stringResource(R.string.bot_calendar_next))
                }
            }
        }
        if (checkBackReadFailed) {
            Text(stringResource(R.string.bot_calendar_check_error), color = MaterialTheme.colorScheme.error)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (calendar) {
                if (timeline.isEmpty() && forecast != null) {
                    item { Text(stringResource(R.string.bot_calendar_empty_day), Modifier.padding(12.dp)) }
                }
                items(timeline, key = { it.key }) { entry ->
                    AgendaCard(entry, owners, jobs, zone, { detailsId = it }, openConversation)
                }
            } else {
                if (jobs.isEmpty() && checkBacks.isEmpty()) {
                    item { Text(stringResource(R.string.bot_calendar_empty), Modifier.padding(12.dp)) }
                }
                val nextByJob = jobs.mapValues { (_, job) ->
                    val retryAt = snapshot.routines[job.id]?.quota?.atMs?.takeIf { job.enabled }
                    listOfNotNull(job.calendarNextRun(), retryAt).minOrNull()
                }
                val orderedJobs = jobs.values.sortedWith(compareBy({ nextByJob[it.id] ?: Long.MAX_VALUE }, { it.name }))
                items(orderedJobs, key = { "job:${it.id}" }) { job ->
                    val state = when {
                        !job.enabled -> stringResource(R.string.bot_routine_paused)
                        nextByJob[job.id] != null ->
                            stringResource(R.string.bot_calendar_next_run, calendarDateTime(nextByJob.getValue(job.id)!!, zone))
                        job.scheduleType == "event" -> stringResource(R.string.bot_calendar_event)
                        else -> stringResource(R.string.bot_calendar_no_next)
                    }
                    CalendarCard(job.name, botName(owners[job.assistantId]), state, null) { detailsId = job.id }
                }
                items(checkBacks.sortedBy { it.at }, key = { it.key }) { entry ->
                    AgendaCard(entry, owners, jobs, zone, { detailsId = it }, openConversation)
                }
                item { Text(stringResource(R.string.bot_calendar_runs), Modifier.padding(top = 10.dp), style = MaterialTheme.typography.titleMedium) }
                if (history.isEmpty()) {
                    item { Text(stringResource(R.string.bot_calendar_no_runs), Modifier.padding(bottom = 12.dp)) }
                }
                items(history, key = { it.key }) { entry ->
                    AgendaCard(entry, owners, jobs, zone, { detailsId = it }, openConversation)
                }
            }
            if (runs.size > RUN_LIMIT) {
                item { Text(stringResource(R.string.bot_calendar_run_limit, RUN_LIMIT), Modifier.padding(12.dp)) }
            }
        }
    }
    if (choosingDate && calendar) {
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { choosingDate = false },
            confirmButton = {
                TextButton(
                    enabled = picker.selectedDateMillis != null,
                    onClick = {
                        picker.selectedDateMillis?.let {
                            epochDay = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
                        }
                        choosingDate = false
                    },
                ) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { choosingDate = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        ) { DatePicker(picker) }
    }

    detailsId?.let(jobs::get)?.let { job ->
        val owner = owners[job.assistantId] ?: return@let
        val info: BotInfoVM = koinViewModel(key = "bot-calendar-${owner.id}", parameters = { parametersOf(owner.id.toString()) })
        AlertDialog(
            onDismissRequest = { detailsId = null },
            title = { Text(job.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(botName(owner))
                    if (job.scheduleType == "cron" || job.scheduleType == "once") {
                        Text(botRoutineScheduleText(parseBotRoutineSchedule(job)))
                    }
                    TextButton(onClick = { info.setRoutineEnabled(job.id, !job.enabled) }) {
                        Text(stringResource(if (job.enabled) R.string.bot_calendar_pause else R.string.bot_calendar_resume))
                    }
                }
            },
            confirmButton = {
                if (job.mode == "llm") {
                    TextButton(onClick = {
                        detailsId = null
                        nav.navigate(Screen.BotInfo(job.assistantId, routineId = job.id))
                    }) { Text(stringResource(R.string.bot_routine_edit)) }
                }
            },
            dismissButton = {
                TextButton(onClick = { detailsId = null }) { Text(stringResource(R.string.bot_calendar_close)) }
            },
        )
    }
}

@Composable
private fun BotCalendarFilter(bots: List<Assistant>, selected: Assistant?, onSelect: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val all = stringResource(R.string.bot_calendar_all_bots)
    Box {
        TextButton(onClick = { expanded = true }) { Text(selected?.let { botName(it) } ?: all) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(all) }, onClick = { expanded = false; onSelect(null) })
            bots.forEach { bot ->
                DropdownMenuItem(text = { Text(botName(bot)) }, onClick = { expanded = false; onSelect(bot.id.toString()) })
            }
        }
    }
}

@Composable
private fun AgendaCard(
    entry: AgendaEntry,
    owners: Map<String, Assistant>,
    jobs: Map<String, ScheduledJobEntity>,
    zone: ZoneId,
    openJob: (String) -> Unit,
    openChat: (String, String, String?) -> Unit,
) {
    val status = when {
        entry.run != null -> stringResource(runStatus(entry.run))
        entry.quotaRetry -> stringResource(R.string.bot_calendar_retry)
        entry.note != null -> stringResource(R.string.bot_calendar_check_back)
        else -> stringResource(R.string.bot_calendar_scheduled)
    }
    val title = jobs[entry.jobId]?.name ?: status
    CalendarCard(
        title = title,
        bot = botName(owners[entry.assistantId]),
        detail = stringResource(R.string.bot_calendar_entry, calendarDateTime(entry.at, zone), status),
        note = entry.note ?: entry.run?.errorMessage,
        onClick = {
            val chat = entry.conversationId
            if (chat != null) openChat(entry.assistantId, chat, entry.jobId)
            else entry.jobId?.let(openJob)
        },
    )
}

@Composable
private fun CalendarCard(title: String, bot: String, detail: String, note: String?, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(bot, style = MaterialTheme.typography.bodySmall)
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            if (!note.isNullOrBlank()) Text(note, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun botName(bot: Assistant?): String =
    bot?.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.assistant_page_default_assistant)

@Composable
private fun calendarDateTime(at: Long, zone: ZoneId): String {
    val context = LocalContext.current
    val use24Hours = DateFormat.is24HourFormat(context)
    val locale = Locale.getDefault()
    val formatter = remember(use24Hours, locale) {
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, if (use24Hours) "MdHm" else "Mdhm"), locale)
    }
    return formatter.format(Instant.ofEpochMilli(at).atZone(zone))
}

private fun runStatus(run: ScheduledJobRunEntity): Int = when {
    run.outcome == "awaiting_approval" -> R.string.routine2_waiting_approval
    run.outcome == "waiting_quota" -> R.string.routine2_waiting_quota
    run.finishedAtMs == null -> R.string.bot_calendar_running
    else -> when (run.outcome) {
        "success" -> R.string.bot_calendar_completed
        "approval_expired", "cancelled" -> R.string.bot_calendar_cancelled
        "skipped", "skipped_catchup", "concurrent_skip" -> R.string.bot_calendar_skipped
        "timed_out" -> R.string.bot_calendar_timeout
        "process_killed_replay" -> R.string.bot_calendar_interrupted
        "failed" -> R.string.bot_calendar_failed
        else -> R.string.bot_calendar_other_result
    }
}
