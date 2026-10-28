package com.clma.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

private const val TAG = "CronAlarmReceiver"

/**
 * Wakeful receiver fired by [android.app.AlarmManager.setExactAndAllowWhileIdle] to ensure
 * scheduled cron jobs trigger on time even during device lock / Doze mode.
 *
 * Acquires a temporary partial wake lock (up to 3 minutes) so the CPU stays awake while
 * WorkManager starts [CronJobWorker]. Once ChatService starts generation,
 * ChatGenerationForegroundService acquires its own wake lock.
 */
class CronAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val jobId = intent.getStringExtra(KEY_JOB_ID) ?: return
        Log.d(TAG, "onReceive: exact alarm fired for job $jobId")

        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = pm?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "clma:cron_alarm_$jobId"
        )?.apply {
            setReferenceCounted(false)
            acquire(3 * 60 * 1000L) // 3 minutes safety timeout
        }

        try {
            val req = OneTimeWorkRequestBuilder<CronJobWorker>()
                .setInputData(Data.Builder().putString(CronJobWorker.KEY_JOB_ID, jobId).build())
                .build()
            // Immediately replace the scheduled work so it runs right now under the wake lock
            WorkManager.getInstance(context).enqueueUniqueWork(
                "cron_job_$jobId",
                ExistingWorkPolicy.REPLACE,
                req
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to enqueue CronJobWorker for job $jobId", e)
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        }
    }

    companion object {
        const val ACTION_CRON_ALARM = "com.clma.app.action.CRON_ALARM"
        const val KEY_JOB_ID = "job_id"
    }
}
