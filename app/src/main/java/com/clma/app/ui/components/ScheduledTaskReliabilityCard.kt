package com.clma.app.ui.components

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.clma.app.R

private data class ScheduledTaskReliability(
    val exactAlarmAccess: Boolean,
    val batteryExemption: Boolean,
) {
    companion object {
        fun read(context: Context) = ScheduledTaskReliability(
            exactAlarmAccess = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(),
            batteryExemption = context.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(context.packageName),
        )

        fun openSettings(context: Context, action: String) {
            val intent = Intent(action, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
    }
}

@Composable
fun ScheduledTaskReliabilityCard() {
    val context = LocalContext.current
    var reliability by remember(context) { mutableStateOf(ScheduledTaskReliability.read(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        reliability = ScheduledTaskReliability.read(context)
    }
    if (reliability.exactAlarmAccess && reliability.batteryExemption) return

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.cron_wake_reliability), style = MaterialTheme.typography.titleSmall)
            if (!reliability.exactAlarmAccess) {
                Text(stringResource(R.string.cron_wake_exact_alarm_hint), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    ScheduledTaskReliability.openSettings(context, Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                }) {
                    Text(stringResource(R.string.cron_wake_exact_alarm_action))
                }
            }
            if (!reliability.batteryExemption) {
                Text(stringResource(R.string.cron_wake_battery_hint), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    ScheduledTaskReliability.openSettings(context, Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                }) {
                    Text(stringResource(R.string.cron_wake_battery_action))
                }
            }
            Text(stringResource(R.string.cron_wake_oem_hint), style = MaterialTheme.typography.bodySmall)
        }
    }
}
