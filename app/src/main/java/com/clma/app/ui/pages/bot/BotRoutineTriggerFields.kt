package com.clma.app.ui.pages.bot

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.PersistableBundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.clma.app.R
import com.clma.app.data.botmode.BotRoutineEventKind
import com.clma.app.data.botmode.BotRoutineEventKind.*
import com.clma.app.data.botmode.BotRoutineTrigger
import com.clma.app.data.model.Assistant
import com.clma.app.service.BotRoutineEvents
import com.clma.app.service.BotRoutineWebhook

internal fun botRoutineTriggerLabel(kind: BotRoutineEventKind): Int = when (kind) {
    NOTIFICATION -> R.string.bot_trigger_notification
    SMS -> R.string.bot_trigger_sms
    EMAIL -> R.string.bot_trigger_email
    WEBHOOK -> R.string.bot_trigger_webhook
    BOT_FINISHED -> R.string.bot_trigger_bot_finished
    POWER_CONNECTED -> R.string.bot_trigger_power_connected
    POWER_DISCONNECTED -> R.string.bot_trigger_power_disconnected
    NETWORK_AVAILABLE -> R.string.bot_trigger_network_available
    NETWORK_LOST -> R.string.bot_trigger_network_lost
    BOOT -> R.string.bot_trigger_boot
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BotRoutineTriggerFields(
    trigger: BotRoutineTrigger?,
    sourceBots: List<Assistant>,
    onChange: (BotRoutineTrigger?) -> Unit,
) {
    Text(stringResource(R.string.bot_trigger_type), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected = trigger == null,
            onClick = { onChange(null) },
            label = { Text(stringResource(R.string.bot_trigger_schedule)) },
        )
        BotRoutineEventKind.entries.forEach { kind ->
            FilterChip(
                selected = trigger?.kind == kind,
                onClick = { if (trigger?.kind != kind) onChange(BotRoutineTrigger(kind)) },
                label = { Text(stringResource(botRoutineTriggerLabel(kind))) },
            )
        }
    }
    when (trigger?.kind) {
        NOTIFICATION, EMAIL -> OutlinedTextField(
            value = trigger.source,
            onValueChange = { onChange(trigger.copy(source = it.trim().take(200))) },
            label = { Text(stringResource(R.string.bot_trigger_package)) },
            supportingText = { Text(stringResource(R.string.bot_trigger_package_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        BOT_FINISHED -> {
            Text(stringResource(R.string.bot_trigger_source_bot), style = MaterialTheme.typography.labelLarge)
            if (sourceBots.isEmpty()) Text(stringResource(R.string.bot_trigger_no_source_bot))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                sourceBots.forEach { bot ->
                    FilterChip(
                        selected = trigger.source == bot.id.toString(),
                        onClick = { onChange(trigger.copy(source = bot.id.toString())) },
                        label = { Text(bot.name) },
                    )
                }
            }
        }
        WEBHOOK -> Text(stringResource(R.string.bot_trigger_webhook_hint))
        POWER_CONNECTED, POWER_DISCONNECTED, NETWORK_AVAILABLE, NETWORK_LOST ->
            Text(stringResource(R.string.bot_trigger_process_hint))
        else -> Unit
    }
    if (trigger?.kind in listOf(NOTIFICATION, EMAIL, SMS, WEBHOOK, BOT_FINISHED)) {
        Text(stringResource(R.string.bot_trigger_context_hint), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun rememberBotRoutinePermission(): (BotRoutineTrigger?, () -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<Pair<BotRoutineEventKind, () -> Unit>?>(null) }
    val complete: () -> Unit = {
        val request = pending
        pending = null
        if (request != null) {
            if (BotRoutineEvents.hasPermission(context, request.first)) request.second()
            else Toast.makeText(context, R.string.bot_trigger_permission_required, Toast.LENGTH_SHORT).show()
        }
    }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { complete() }
    val sms = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { complete() }
    return { trigger, onGranted ->
        when {
            trigger == null || BotRoutineEvents.hasPermission(context, trigger.kind) -> onGranted()
            pending == null -> {
                pending = trigger.kind to onGranted
                runCatching {
                    if (trigger.kind == SMS) sms.launch(Manifest.permission.RECEIVE_SMS)
                    else settings.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }.onFailure { complete() }
            }
            else -> Unit
        }
    }
}

@Composable
internal fun BotRoutineWebhookCopy(id: String, trigger: BotRoutineTrigger) {
    val context = LocalContext.current
    TextButton(onClick = {
        val clip = ClipData.newPlainText("Webhook", BotRoutineWebhook.url(id, trigger))
        clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
    }) {
        Text(stringResource(R.string.bot_trigger_copy_webhook))
    }
}
