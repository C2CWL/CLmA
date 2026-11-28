// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), server/host/brief.ts, server/host/brief-cards.ts, server/host/brief-words.ts,
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0 (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.clma.ai.core.MessageRole
import com.clma.ai.ui.UIMessage
import com.clma.app.R
import com.clma.app.data.botmode.BotBriefStore
import com.clma.app.data.botmode.BotGoal
import com.clma.app.data.botmode.botAssistants
import com.clma.app.data.botmode.botBriefWindow
import com.clma.app.data.botmode.briefCoordinatorId
import com.clma.app.data.botmode.briefExcerpt
import com.clma.app.data.botmode.channelConversationId
import com.clma.app.data.botmode.dailyBriefTopicId
import com.clma.app.data.botmode.goalProgress
import com.clma.app.data.botmode.isDailyBriefTopic
import com.clma.app.data.botmode.savedGoal
import com.clma.app.data.botmode.settings.BotModeSettingsStore
import com.clma.app.data.botmode.settings.BotProfileStore
import com.clma.app.data.botmode.settings.BotToolNames
import com.clma.app.data.botmode.settings.notificationSettings
import com.clma.app.data.datastore.Settings
import com.clma.app.data.datastore.SettingsStore
import com.clma.app.data.model.Conversation
import com.clma.app.data.repository.ConversationRepository
import com.clma.app.data.repository.ScheduledJobRepository
import com.clma.app.data.repository.ScheduledJobRunRepository
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.datetime.toJavaLocalDateTime
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class BotDailyBriefWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params), KoinComponent {

    private val settingsStore: SettingsStore by inject()
    private val conversations: ConversationRepository by inject()
    private val jobs: ScheduledJobRepository by inject()
    private val runs: ScheduledJobRunRepository by inject()
    private val profiles: BotProfileStore by inject()
    private val notifications: BotModeSettingsStore by inject()
    private val store = BotBriefStore(context)

    override suspend fun doWork(): Result = try {
        deliver()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("BotDailyBrief", "Could not deliver the daily brief", e)
        Result.retry()
    }

    private suspend fun deliver() {
        val schedule = store.schedule.first() ?: return
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val window = botBriefWindow(now, schedule.time, zone)
        if (window.due < schedule.changedAt || store.deliveredOn(window.day)) return

        val settings = settingsStore.settingsFlowRaw.first()
        val coordinator = settings.briefCoordinatorId(profiles.profiles.first()) ?: return
        val briefId = dailyBriefTopicId(window.day)
        val existing = conversations.getConversationById(briefId)
        val title = "${text(R.string.bot_brief_title)} · ${window.day}"
        if (existing == null) {
            val brief = buildBrief(settings, window.since, window.due, now, zone)
            if (store.schedule.first() != schedule) return
            val current = settingsStore.settingsFlowRaw.first()
            if (current.briefCoordinatorId(profiles.profiles.first()) != coordinator) return
            conversations.insertConversation(
                Conversation(id = briefId, assistantId = coordinator, title = title, messageNodes = emptyList())
                    .updateCurrentMessages(listOf(UIMessage.assistant("## $title\n\n$brief"))),
            )
        }

        val owner = existing?.assistantId ?: coordinator
        val currentSettings = settingsStore.settingsFlowRaw.first()
        if (currentSettings.botAssistants().any { it.id == owner }) {
            val display = currentSettings.displaySetting
            BotFeedback.deliver(
                context = applicationContext,
                settings = notifications.notificationSettings(),
                event = BotFeedbackEvent.ROUTINE_RESULT,
                channelId = BOT_ROUTINE_NOTIFICATION_CHANNEL_ID,
                showNotification = display.enableNotificationOnMessageGeneration,
                allowVibration = display.enableMessageGenerationHapticEffect,
            ) {
                BotRoutineRuns.notifyBrief(applicationContext, briefId.toString())
            }
        }
        store.markDelivered(window.day)
    }

    private suspend fun buildBrief(
        settings: Settings,
        since: Instant,
        until: Instant,
        now: Instant,
        zone: ZoneId,
    ): String {
        val activity = mutableListOf<String>()
        val needs = mutableListOf<String>()
        val goals = mutableListOf<String>()
        val routineResults = mutableListOf<String>()
        val bots = settings.botAssistants()
        val savedGoals = store.conversationGoals().first()
        for (bot in bots) {
            val name = briefExcerpt(bot.name.ifBlank { text(R.string.bot_routine_default_bot_name) }, 80)
            val channelTopics = settings.botChannelsFor(bot.id)
            val summaries = conversations.getConversationsOfAssistant(bot.id).first()
            var replyCount = 0
            val highlights = mutableListOf<Pair<Instant, String>>()
            for (summary in summaries) {
                if (isDailyBriefTopic(summary.id)) continue
                val conversation = conversations.getConversationById(summary.id)
                    ?.takeIf { it.assistantId == bot.id } ?: continue
                val messages = conversation.currentMessages
                val label = briefExcerpt(channelTopics[summary.id]?.name ?: conversation.title, 80)
                    .ifBlank { text(R.string.bot_brief_untitled) }
                val (questions, approvals) = PendingToolInteractions.pendingTools(messages)
                    .partition { BotToolNames.asksHuman(it.toolName) }
                if (questions.isNotEmpty()) needs += text(R.string.bot_brief_question, name, label)
                if (approvals.isNotEmpty()) needs += text(R.string.bot_brief_approval, name, label)
                val replies = messages.asSequence()
                    .dropWhile { it.role != MessageRole.USER }
                    .filter { it.role == MessageRole.ASSISTANT }
                    .mapNotNull { message ->
                        val body = message.toText().takeIf(String::isNotBlank) ?: return@mapNotNull null
                        val created = message.createdAt.toJavaLocalDateTime().atZone(zone).toInstant()
                        val finished = message.finishedAt?.toJavaLocalDateTime()?.atZone(zone)?.toInstant()
                        val at = finished ?: created
                        if (at > now) null else Reply(at, created, body, finished != null)
                    }
                    .toList()
                val recent = replies.filter { it.at >= since && it.at < until }
                replyCount += recent.size
                recent.lastOrNull()?.let { reply ->
                    highlights += reply.at to text(R.string.bot_brief_topic_line, label, briefExcerpt(reply.text))
                }
                val channel = channelTopics[summary.id]
                val goal = if (channel == null) savedGoals[summary.id] else channel.savedGoal()
                goal?.let { goals += goalLine(name, label, it, replies) }
            }
            val existingIds = summaries.mapTo(hashSetOf()) { it.id }
            for ((id, channel) in channelTopics) {
                if (id in existingIds) continue
                channel.savedGoal()?.let {
                    goals += goalLine(name, briefExcerpt(channel.name, 80), it, emptyList())
                }
            }
            activity += if (replyCount == 0) text(R.string.bot_brief_no_activity, name)
            else text(R.string.bot_brief_activity_count, name, replyCount) + highlights
                .sortedByDescending { it.first }.take(3).joinToString("") { "\n  - ${it.second}" }
        }

        val botIds = bots.mapTo(hashSetOf()) { it.id.toString() }
        for (job in jobs.getAll().filter { it.assistantId in botIds }) {
            val owner = bots.first { it.id.toString() == job.assistantId }
            val label = text(R.string.bot_brief_topic_line, briefExcerpt(owner.name, 80), briefExcerpt(job.name, 80))
            val latest = runs.lastFinished(job.id, since.toEpochMilli(), until.toEpochMilli()) ?: continue
            if (latest.outcome == "success") {
                val reply = latest.conversationId?.let(Uuid::parseOrNull)
                    ?.let { conversations.getConversationById(it) }
                    ?.takeIf { it.assistantId == owner.id }
                    ?.currentMessages?.lastOrNull { it.role == MessageRole.ASSISTANT && it.toText().isNotBlank() }
                    ?.toText().orEmpty()
                routineResults += text(R.string.bot_brief_routine_done, label) +
                    if (reply.isBlank()) "" else " · ${briefExcerpt(reply)}"
            } else {
                val status = when (latest.outcome) {
                    "skipped_catchup", "concurrent_skip" -> R.string.bot_brief_routine_skipped
                    else -> R.string.bot_brief_routine_failed
                }
                val detail = latest.errorMessage.orEmpty()
                val result = text(status, label) + if (detail.isBlank()) "" else " · ${briefExcerpt(detail)}"
                routineResults += result
                if (status == R.string.bot_brief_routine_failed || latest.outcome == "skipped_catchup") needs += result
            }
        }

        return listOf(
            section(R.string.bot_brief_needs, needs.ifEmpty { listOf(text(R.string.bot_brief_nothing_needs)) }),
            section(R.string.bot_brief_activity, activity),
            section(R.string.bot_brief_routines, routineResults.ifEmpty { listOf(text(R.string.bot_brief_no_routines)) }),
            section(R.string.bot_brief_goals, goals),
        ).filter(String::isNotEmpty).joinToString("\n\n")
    }

    private fun Settings.botChannelsFor(botId: Uuid) = displaySetting.botChannels
        .filter { botId in it.memberAssistantIds }.associateBy { channelConversationId(it.id, botId) }

    private data class Reply(val at: Instant, val startedAt: Instant, val text: String, val finished: Boolean)

    private fun goalLine(bot: String, topic: String, goal: BotGoal, replies: List<Reply>): String {
        val update = replies.asReversed().firstNotNullOfOrNull { reply ->
            if (reply.finished && reply.startedAt.toEpochMilli() >= goal.updatedAtMs) goalProgress(reply.text) else null
        }
        return text(
            R.string.bot_brief_goal_line, bot, topic, briefExcerpt(goal.text),
            update?.let { briefExcerpt(it) } ?: text(R.string.bot_brief_no_progress),
        )
    }

    private fun section(title: Int, rows: List<String>): String {
        if (rows.isEmpty()) return ""
        val visible = rows.take(30).toMutableList()
        if (rows.size > visible.size) visible += text(R.string.bot_brief_more, rows.size - visible.size)
        return "### ${text(title)}\n\n" + visible.joinToString("\n") { "- $it" }
    }

    private fun text(id: Int, vararg args: Any): String = applicationContext.getString(id, *args)

    companion object {
        private const val WORK_NAME = "bot_daily_brief"

        suspend fun observeSchedule(context: Context) {
            val manager = WorkManager.getInstance(context)
            BotBriefStore(context).schedule.collect { schedule ->
                if (schedule == null) {
                    manager.cancelUniqueWork(WORK_NAME)
                } else {
                    val work = PeriodicWorkRequestBuilder<BotDailyBriefWorker>(15, TimeUnit.MINUTES).build()
                    manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, work)
                }
            }
        }
    }
}
