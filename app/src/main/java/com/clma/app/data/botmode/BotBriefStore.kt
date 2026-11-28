package com.clma.app.data.botmode

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.clma.app.data.botmode.settings.BotProfile
import com.clma.app.data.datastore.BotChannel
import com.clma.app.data.datastore.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.uuid.Uuid

private val Context.botBriefDataStore by preferencesDataStore(name = "bot_brief")
private val scheduleKey = intPreferencesKey("minute_of_day")
private val scheduleChangedKey = longPreferencesKey("schedule_changed_at")
private val lastDayKey = stringPreferencesKey("last_delivery_day")

internal data class BotBriefSchedule(val time: LocalTime, val changedAt: Instant)

class BotBriefStore(context: Context) {
    private val store = context.applicationContext.botBriefDataStore

    internal val schedule: Flow<BotBriefSchedule?> = store.data.map { snapshot ->
        snapshot[scheduleKey]?.let {
            BotBriefSchedule(
                LocalTime.of(it / 60, it % 60),
                Instant.ofEpochMilli(snapshot[scheduleChangedKey] ?: 0),
            )
        }
    }.distinctUntilChanged()

    val scheduledTime: Flow<LocalTime?> = schedule.map { it?.time }.distinctUntilChanged()

    suspend fun setScheduledTime(time: LocalTime?) {
        store.edit { snapshot ->
            val minute = time?.let { it.hour * 60 + it.minute }
            if (minute == snapshot[scheduleKey]) return@edit
            if (minute == null) snapshot.remove(scheduleKey)
            else snapshot[scheduleKey] = minute
            snapshot[scheduleChangedKey] = System.currentTimeMillis()
        }
    }

    suspend fun deliveredOn(day: LocalDate): Boolean = store.data.first()[lastDayKey]?.let { it >= day.toString() } == true

    suspend fun markDelivered(day: LocalDate) {
        store.edit { it[lastDayKey] = day.toString() }
    }

    private fun textKey(id: Uuid) = stringPreferencesKey("goal_$id")
    private fun timeKey(id: Uuid) = longPreferencesKey("goal_time_$id")

    suspend fun setConversationGoal(id: Uuid, goal: String) {
        val text = goal.trim().take(BOT_GOAL_MAX_LENGTH)
        store.edit { snapshot ->
            if (text == snapshot[textKey(id)].orEmpty()) return@edit
            if (text.isEmpty()) {
                snapshot.remove(textKey(id))
                snapshot.remove(timeKey(id))
            } else {
                snapshot[textKey(id)] = text
                snapshot[timeKey(id)] = System.currentTimeMillis()
            }
        }
    }

    fun conversationGoals(): Flow<Map<Uuid, BotGoal>> = store.data.map { snapshot ->
        snapshot.asMap().keys.mapNotNull { key ->
            if (!key.name.startsWith("goal_") || key.name.startsWith("goal_time_")) return@mapNotNull null
            val id = Uuid.parseOrNull(key.name.removePrefix("goal_")) ?: return@mapNotNull null
            val text = snapshot[textKey(id)] ?: return@mapNotNull null
            id to BotGoal(text, snapshot[timeKey(id)] ?: 0)
        }.toMap()
    }.distinctUntilChanged()

    suspend fun goal(settings: Settings, botId: Uuid, conversationId: Uuid): BotGoal? {
        if (!settings.isBotAssistantId(botId)) return null
        val channel = settings.goalChannel(botId, conversationId)
        if (channel != null) return channel.savedGoal()
        val snapshot = store.data.first()
        return snapshot[textKey(conversationId)]?.let { BotGoal(it, snapshot[timeKey(conversationId)] ?: 0) }
    }
}

internal fun Settings.briefCoordinatorId(profiles: Map<String, BotProfile>): Uuid? =
    botAssistants().firstOrNull { profiles[it.id.toString()]?.chiefOfStaff == true }?.id ?: mainBot()?.id

fun Settings.goalChannel(botId: Uuid, topicId: Uuid): BotChannel? =
    displaySetting.botChannels.firstOrNull {
        botId in it.memberAssistantIds && channelConversationId(it.id, botId) == topicId
    }

internal fun BotChannel.savedGoal(): BotGoal? =
    goal.takeIf(String::isNotBlank)?.let { BotGoal(it, goalUpdatedAtMs) }
