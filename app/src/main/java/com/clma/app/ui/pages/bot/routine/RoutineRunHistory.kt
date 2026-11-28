// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), src/components/RoutineRunCard.tsx,
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0
// (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.ui.pages.bot.routine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.clma.app.R
import com.clma.app.data.model.BotMascotChoice
import com.clma.app.ui.pages.bot.BotMascot
import com.clma.app.ui.pages.bot.BotMascotState
import com.clma.app.data.db.entity.ScheduledJobRunEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.uuid.Uuid

@Composable
internal fun RoutineRunHistory(
    runs: List<ScheduledJobRunEntity>,
    zone: ZoneId,
    mascot: BotMascotChoice,
    onOpenConversation: (Uuid) -> Unit,
) {
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone)
    Text(stringResource(R.string.routine2_history), style = MaterialTheme.typography.titleSmall)
    if (runs.isEmpty()) Text(stringResource(R.string.routine2_history_empty), style = MaterialTheme.typography.bodySmall)
    runs.forEach { run ->
        val label = when {
            run.finishedAtMs == null -> R.string.routine2_running
            else -> when (run.outcome) {
                "success" -> R.string.routine2_success
                "awaiting_approval" -> R.string.routine2_waiting_approval
                "waiting_quota" -> R.string.routine2_waiting_quota
                "cancelled", "approval_expired" -> R.string.routine2_cancelled
                "skipped", "concurrent_skip", "skipped_catchup" -> R.string.routine2_skipped
                else -> R.string.routine2_failed
            }
        }
        val color = when (label) {
            R.string.routine2_failed -> MaterialTheme.colorScheme.errorContainer
            R.string.routine2_waiting_quota, R.string.routine2_waiting_approval -> MaterialTheme.colorScheme.tertiaryContainer
            else -> MaterialTheme.colorScheme.secondaryContainer
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val mascotState = when (label) {
                    R.string.routine2_running -> BotMascotState.WORKING
                    R.string.routine2_success -> BotMascotState.DONE
                    R.string.routine2_waiting_approval -> BotMascotState.WAITING
                    R.string.routine2_waiting_quota, R.string.routine2_failed -> BotMascotState.BLOCKED
                    else -> null
                }
                mascotState?.let { BotMascot(it, mascot) }
                Surface(shape = MaterialTheme.shapes.small, color = color) {
                    Text(stringResource(label), Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall)
                }
                Text(formatter.format(Instant.ofEpochMilli(run.scheduledAtMs)), style = MaterialTheme.typography.bodySmall)
            }
            val detail = when (run.errorMessage) {
                "overlap" -> stringResource(R.string.routine2_overlap_reason)
                "check_not_met" -> stringResource(R.string.routine2_check_not_met)
                "check_paced" -> stringResource(R.string.routine2_check_paced)
                "check_unavailable" -> stringResource(R.string.routine2_check_unavailable)
                "conversation_busy" -> stringResource(R.string.routine2_conversation_busy)
                else -> if (run.outcome == "waiting_quota") {
                    run.errorMessage?.toLongOrNull()?.let {
                        val time = DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(Instant.ofEpochMilli(it))
                        stringResource(R.string.routine2_quota_resumes, time)
                    }
                } else run.errorMessage
            }
            if (!detail.isNullOrBlank()) Text(detail, style = MaterialTheme.typography.bodySmall)
            run.conversationId?.let { runCatching { Uuid.parse(it) }.getOrNull() }?.let { id ->
                TextButton(onClick = { onOpenConversation(id) }) { Text(stringResource(R.string.routine2_open_result)) }
            }
        }
    }
}
