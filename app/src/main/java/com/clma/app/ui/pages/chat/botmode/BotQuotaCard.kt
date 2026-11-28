// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), src/locales/en.json, src/locales/zh.json, server/routine-recovery.ts,
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0
// (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.ui.pages.chat.botmode

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.clma.app.R
import com.clma.app.service.BotQuotaWait
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun BotQuotaCard(
    wait: BotQuotaWait,
    busy: Boolean,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val reset = wait.resetAt?.let { Instant.ofEpochMilli(it).atZone(zone) }
    val resetTime = reset?.let {
        val pattern = if (wait.retryAt != null) "HH:mm" else "MM-dd HH:mm"
        it.format(DateTimeFormatter.ofPattern(pattern))
    }
    val status = when {
        resetTime == null -> stringResource(R.string.bot_quota_reached)
        wait.retryAt == null -> stringResource(R.string.bot_quota_reset_at, resetTime)
        else -> stringResource(R.string.bot_quota_resumes_at, resetTime)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(status)
            Row {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.bot_quota_cancel_turn)) }
                TextButton(onClick = onRetry, enabled = !busy) { Text(stringResource(R.string.bot_quota_retry_now)) }
            }
        }
    }
}
