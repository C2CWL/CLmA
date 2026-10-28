package com.clma.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.clma.app.data.datastore.SettingsStore
import com.clma.app.data.db.entity.ScheduledJobEntity
import com.clma.app.data.repository.ScheduledJobRepository
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.math.max

private const val TAG = "CronJobScheduler"

/**
 * Schedules / unschedules cron job executions via WorkManager and AlarmManager.
 * Each scheduled job has a unique work name "cron_job_<id>" and an exact alarm
 * set via [AlarmManager.setExactAndAllowWhileIdle] to pierce Doze mode / lock screen.
 *
 * Recurring jobs re-schedule themselves at the end of CronJobWorker.doWork(). Boot
 * recovery happens via CronBootReceiver which calls scheduleAllEnabled().
 */
class CronJobScheduler(
    private val context: Context,
    private val repo: ScheduledJobRepository,
    private val settingsStore: SettingsStore? = null,
) {
    private val wm get() = WorkManager.getInstance(context)

    suspend fun schedule(job: ScheduledJobEntity) {
        val nowMs = System.currentTimeMillis()
        val defaultZone = resolveDefaultZone()
        val nextRun = nextRunMs(job, nowMs, defaultZone)
        if (nextRun == null) {
            repo.update(terminalJobUpdate(job))
            cancel(job.id)
            return
        }
        repo.update(job.copy(nextRunAtMs = nextRun))
        val delayMs = max(0L, nextRun - nowMs)
        val req = OneTimeWorkRequestBuilder<CronJobWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putString(CronJobWorker.KEY_JOB_ID, job.id).build())
            .build()
        wm.enqueueUniqueWork(workNameFor(job.id), ExistingWorkPolicy.REPLACE, req)
        setExactAlarm(job.id, nextRun)
    }

    /**
     * Trigger a manual fire (trigger_job_now). Distinct work name from the regular schedule
     * + sets KEY_MANUAL=true so the worker skips lastRunAtMs / runs_so_far bumps. Manual
     * fires are bonus — they don't disturb the regular schedule's accounting.
     */
    suspend fun triggerNow(jobId: String) {
        val req = OneTimeWorkRequestBuilder<CronJobWorker>()
            .setInitialDelay(0L, TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder()
                .putString(CronJobWorker.KEY_JOB_ID, jobId)
                .putBoolean(CronJobWorker.KEY_MANUAL, true)
                .build())
            .build()
        wm.enqueueUniqueWork(manualWorkNameFor(jobId), ExistingWorkPolicy.REPLACE, req)
    }

    fun cancel(jobId: String) {
        wm.cancelUniqueWork(workNameFor(jobId))
        cancelExactAlarm(jobId)
    }

    suspend fun scheduleAllEnabled() {
        repo.getEnabled().forEach { schedule(it) }
    }

    private fun resolveDefaultZone(): ZoneId {
        val customTz = settingsStore?.settingsFlow?.value?.customTimezone
        return customTz?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
    }

    private fun getAlarmPendingIntent(jobId: String): PendingIntent {
        val intent = Intent(context, CronAlarmReceiver::class.java).apply {
            action = CronAlarmReceiver.ACTION_CRON_ALARM
            putExtra(CronAlarmReceiver.KEY_JOB_ID, jobId)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, jobId.hashCode(), intent, flags)
    }

    private fun setExactAlarm(jobId: String, triggerAtMs: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pendingIntent = getAlarmPendingIntent(jobId)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to schedule exact alarm for job $jobId", e)
        }
    }

    private fun cancelExactAlarm(jobId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pendingIntent = getAlarmPendingIntent(jobId)
        try {
            alarmManager.cancel(pendingIntent)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to cancel exact alarm for job $jobId", e)
        }
    }

    private fun workNameFor(jobId: String) = "cron_job_$jobId"
    private fun manualWorkNameFor(jobId: String) = "cron_job_${jobId}_manual"

    companion object {
        /**
         * Compute the next fire time given [nowMs]. Returns null if the job will never
         * fire again (disabled, max_runs reached, end_at past, once already fired).
         *
         * Pure function — no side effects, no Room access. Lives in the companion so
         * tests + the boot receiver can call it without instantiating a scheduler.
         */
        fun nextRunMs(
            job: ScheduledJobEntity,
            nowMs: Long,
            defaultZone: ZoneId = ZoneId.systemDefault(),
        ): Long? {
            if (!job.enabled) return null
            if (job.maxRuns != null && job.runsSoFar >= job.maxRuns) return null
            if (job.endAtUnixMs != null && nowMs > job.endAtUnixMs) return null

            return when (job.scheduleType) {
                "once" -> {
                    val at = job.atUnixMs ?: return null
                    if (job.lastRunAtMs != null) null else at
                }
                "cron" -> {
                    val expr = job.cronExpression ?: return null
                    val zone = job.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: defaultZone
                    val cron = CronExpressionParser.parse(expr).getOrNull() ?: return null
                    val basisMs = max(nowMs, job.startAtUnixMs ?: 0L) - 1L
                    val basisZdt = Instant.ofEpochMilli(basisMs).atZone(zone)
                    val nextZdt = CronExpressionParser.nextExecution(cron, basisZdt) ?: return null
                    val next = nextZdt.toInstant().toEpochMilli()
                    if (job.endAtUnixMs != null && next > job.endAtUnixMs) null else next
                }
                else -> null
            }
        }

        /**
         * Pure. Decides what to persist when [nextRunMs] returned null (schedule() is about
         * to [cancel] the job's WorkManager work). Every other reason nextRunMs returns null
         * (disabled, max_runs reached, end_at past, a "once" job that already fired) already
         * has `enabled` reflecting reality by the time schedule() runs. An unparseable cron
         * expression does not: cron_expression validation was tightened after some jobs may
         * have been persisted (e.g. an out-of-range "@every 90m"), so a pre-existing row can
         * newly fail to parse. Without this, schedule() would cancel the work forever while
         * the job row still shows enabled=true, silently zombied with no way to tell.
         */
        internal fun terminalJobUpdate(job: ScheduledJobEntity): ScheduledJobEntity {
            val unparseable = job.scheduleType == "cron" && job.cronExpression != null &&
                CronExpressionParser.parse(job.cronExpression).isFailure
            return job.copy(
                nextRunAtMs = null,
                enabled = if (unparseable) false else job.enabled,
            )
        }
    }
}
