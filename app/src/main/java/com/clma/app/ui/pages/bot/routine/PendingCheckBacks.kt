// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), src/components/CheckBackLine.tsx,
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0
// (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.ui.pages.bot.routine

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.clma.app.R
import com.clma.app.data.botmode.routine.CheckBack
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.uuid.Uuid

@Composable
internal fun PendingCheckBacks(
    pending: List<CheckBack>,
    zone: ZoneId,
    onOpen: (Uuid) -> Unit,
    onCancel: (CheckBack) -> Unit,
) {
    if (pending.isEmpty()) return
    val format = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(zone)
    Text(stringResource(R.string.routine2_check_back_title), style = MaterialTheme.typography.titleSmall)
    pending.sortedBy { it.atMs }.forEach { item ->
        Row {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.routine2_check_back_pending, format.format(Instant.ofEpochMilli(item.atMs)), item.note))
                TextButton(onClick = { onOpen(Uuid.parse(item.conversationId)) }) {
                    Text(stringResource(R.string.routine2_open_result))
                }
            }
            TextButton(onClick = { onCancel(item) }) { Text(stringResource(R.string.bot_home_cancel)) }
        }
    }
}
