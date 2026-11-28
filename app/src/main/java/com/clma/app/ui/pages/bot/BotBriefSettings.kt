package com.clma.app.ui.pages.bot

import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clma.app.R
import com.clma.app.data.botmode.BOT_GOAL_MAX_LENGTH
import com.clma.app.data.botmode.BotBriefStore
import com.clma.app.data.botmode.briefCoordinatorId
import com.clma.app.data.botmode.goalChannel
import com.clma.app.data.botmode.settings.BotProfileStore
import com.clma.app.data.datastore.Settings
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.time.LocalTime
import java.util.Calendar
import kotlin.uuid.Uuid

@Composable
internal fun BotBriefSettings(settings: Settings, botId: Uuid, conversationId: Uuid?) {
    val context = LocalContext.current
    val store = remember(context) { BotBriefStore(context) }
    val profileStore: BotProfileStore = koinInject()
    val profiles by profileStore.profiles.collectAsStateWithLifecycle(initialValue = null)
    if (profiles?.let(settings::briefCoordinatorId) == botId) DailyBriefSchedule(store)

    if (conversationId != null && settings.goalChannel(botId, conversationId) == null) {
        ConversationGoalEditor(store, conversationId)
    }
}

@Composable
private fun DailyBriefSchedule(store: BotBriefStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val time by store.scheduledTime.collectAsStateWithLifecycle(initialValue = null)
    val selected = time
    ListItem(
        headlineContent = { Text(stringResource(R.string.bot_brief_title)) },
        supportingContent = { Text(stringResource(R.string.bot_brief_schedule_help)) },
        trailingContent = {
            TextButton(onClick = {
                val start = selected ?: LocalTime.of(8, 0)
                TimePickerDialog(context, { _, hour, minute ->
                    scope.launch { store.setScheduledTime(LocalTime.of(hour, minute)) }
                }, start.hour, start.minute, DateFormat.is24HourFormat(context)).show()
            }) {
                val formatted = selected?.let {
                    val calendar = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, it.hour)
                        set(Calendar.MINUTE, it.minute)
                    }
                    DateFormat.getTimeFormat(context).format(calendar.time)
                }
                Text(formatted ?: stringResource(R.string.bot_brief_set_time))
            }
        },
    )
    if (selected != null) {
        TextButton(
            onClick = { scope.launch { store.setScheduledTime(null) } },
            modifier = Modifier.padding(horizontal = 12.dp),
        ) {
            Text(stringResource(R.string.bot_brief_turn_off))
        }
    }
}

@Composable
private fun ConversationGoalEditor(store: BotBriefStore, id: Uuid) {
    val scope = rememberCoroutineScope()
    val goals by remember(store) { store.conversationGoals() }.collectAsStateWithLifecycle(initialValue = null)
    val loaded = goals ?: return
    val current = loaded[id]?.text.orEmpty()
    var editing by rememberSaveable(id.toString()) { mutableStateOf(false) }
    var draft by rememberSaveable(id.toString()) { mutableStateOf("") }
    ListItem(
        headlineContent = { Text(stringResource(R.string.bot_goal_conversation)) },
        supportingContent = {
            Text(current.ifBlank { stringResource(R.string.bot_goal_help) }, maxLines = 3)
        },
        trailingContent = {
            TextButton(onClick = {
                draft = current
                editing = true
            }) {
                Text(stringResource(R.string.bot_goal_edit))
            }
        },
    )
    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(stringResource(R.string.bot_goal_conversation)) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(BOT_GOAL_MAX_LENGTH) },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val value = draft
                    scope.launch {
                        store.setConversationGoal(id, value)
                        editing = false
                    }
                }) { Text(stringResource(R.string.bot_mode_channels_save)) }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }) { Text(stringResource(R.string.bot_home_cancel)) }
            },
        )
    }
}
