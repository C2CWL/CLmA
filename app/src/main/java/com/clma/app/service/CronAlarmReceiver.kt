package com.clma.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager

class CronAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val input = scheduledAlarmInput(intent) ?: return
        try {
            // An exact alarm is one of the cases where Android 12+ lets a background app start a
            // foreground service, so the run starts now, even in Doze, instead of waiting for a
            // WorkManager maintenance window.
            ContextCompat.startForegroundService(
                context,
                Intent(context, ScheduledRunService::class.java).putExtras(intent),
            )
        } catch (error: Exception) {
            Log.w("CronAlarmReceiver", "Unable to start scheduled run service", error)
            enqueueAlarmFallback(context, input)
        }
    }

    companion object {
        const val ACTION_CRON_ALARM = "com.clma.app.action.CRON_ALARM"
        const val KEY_JOB_ID = "job_id"
    }
}

internal fun scheduledAlarmInput(intent: Intent): Data? {
    val jobId = intent.getStringExtra(CronAlarmReceiver.KEY_JOB_ID) ?: return null
    return Data.Builder().apply {
        putString(CronJobWorker.KEY_JOB_ID, jobId)
        val slotKey = CronJobWorker.KEY_SCHEDULED_AT_MS
        if (intent.hasExtra(slotKey)) putLong(slotKey, intent.getLongExtra(slotKey, 0L))
    }.build()
}

internal fun enqueueAlarmFallback(context: Context, input: Data) {
    val jobId = input.getString(CronJobWorker.KEY_JOB_ID) ?: return
    try {
        val request = OneTimeWorkRequestBuilder<CronJobWorker>()
            .setInputData(input)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "cron_job_${jobId}_alarm",
            ExistingWorkPolicy.KEEP,
            request,
        )
    } catch (error: Exception) {
        Log.e("CronAlarmReceiver", "Unable to enqueue scheduled run fallback for $jobId", error)
    }
}
