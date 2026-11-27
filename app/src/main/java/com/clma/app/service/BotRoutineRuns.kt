package com.clma.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.clma.app.R
import com.clma.app.RouteActivity
import com.clma.app.utils.sendNotification
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

/** Notification channel for Bot routine results, approvals and cancellations. */
const val BOT_ROUTINE_NOTIFICATION_CHANNEL_ID = "bot_routines"

/**
 * Glue between Bot routine runs and Android: re-queueing a parked run once the user answered,
 * the give-up timer for an unanswered one, the exact-alarm permission check, and the short
 * notifications that deep-link into the bot's chat.
 *
 * The run itself stays in [CronJobWorker]; resume and expiry are the same worker class with a
 * different input key, so WorkManager constraints and Koin wiring are shared.
 */
object BotRoutineRuns {

    private fun resumeWorkName(conversationId: String) = "bot_routine_resume_$conversationId"
    private fun expiryWorkName(conversationId: String) = "bot_routine_expire_$conversationId"

    /** Every approval card of a parked run is answered: continue it in a worker. */
    fun requestResume(context: Context, conversationId: Uuid) {
        val id = conversationId.toString()
        val req = OneTimeWorkRequestBuilder<CronJobWorker>()
            .setInputData(Data.Builder().putString(CronJobWorker.KEY_RESUME_CONVERSATION, id).build())
            .build()
        // APPEND_OR_REPLACE: a resume already queued or running is never cancelled by a later
        // request; the later one runs after it and is a no-op unless the run parked again.
        WorkManager.getInstance(context).enqueueUniqueWork(resumeWorkName(id), ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    /**
     * The routine behind parked run(s) was deleted: let the worker settle each one now (close
     * its cards, cancel the run) instead of waiting out the approval deadline. The topic stays.
     */
    fun settleNow(context: Context, conversationId: String) {
        scheduleExpiry(context, conversationId, 0L)
    }

    /** Cancel the parked run if nobody answers within [delayMs]. */
    fun scheduleExpiry(context: Context, conversationId: String, delayMs: Long) {
        val req = OneTimeWorkRequestBuilder<CronJobWorker>()
            .setInitialDelay(delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putString(CronJobWorker.KEY_EXPIRE_CONVERSATION, conversationId).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(expiryWorkName(conversationId), ExistingWorkPolicy.REPLACE, req)
    }

    fun cancelExpiry(context: Context, conversationId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(expiryWorkName(conversationId))
    }

    /**
     * False only on Android 12+ when the user has not allowed exact alarms: routines then fire
     * through inexact alarms / WorkManager and can run late.
     */
    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return true
        return runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(true)
    }

    /** Settings screen where the user grants "Alarms & reminders" for this app (Android 12+). */
    fun exactAlarmSettingsIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            .setData(android.net.Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    // ------------------------------------------------------------ notifications

    private fun notificationIdFor(conversationId: String): Int =
        (conversationId.hashCode() and 0x0FFFFFFF) or 0x20000000

    private fun openChatIntent(context: Context, conversationId: String): PendingIntent {
        val intent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("conversationId", conversationId)
        }
        return PendingIntent.getActivity(
            context,
            // Own request-code space (see ChatService.getPendingIntent for why it matters).
            notificationIdFor(conversationId),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun post(context: Context, conversationId: String, title: String, text: String) {
        runCatching {
            context.sendNotification(
                channelId = BOT_ROUTINE_NOTIFICATION_CHANNEL_ID,
                notificationId = notificationIdFor(conversationId),
            ) {
                this.title = title
                content = text
                autoCancel = true
                useDefaults = true
                useBigTextStyle = true
                category = NotificationCompat.CATEGORY_MESSAGE
                contentIntent = openChatIntent(context, conversationId)
            }
        }
    }

    /** A run finished: always posted for Bot routines, with a short line of the result. */
    fun notifyDone(context: Context, conversationId: String, botName: String, jobName: String, summary: String) {
        val line = summary.replace(Regex("\\s+"), " ").trim().take(120)
        post(
            context,
            conversationId,
            context.getString(R.string.bot_routine_notify_done_title, botName),
            if (line.isEmpty()) jobName else context.getString(R.string.bot_routine_notify_line, jobName, line),
        )
    }

    fun notifyFailed(context: Context, conversationId: String, botName: String, jobName: String, reason: String) {
        post(
            context,
            conversationId,
            context.getString(R.string.bot_routine_notify_failed_title, botName),
            context.getString(R.string.bot_routine_notify_line, jobName, reason.take(120)),
        )
    }

    fun notifyApprovalNeeded(context: Context, conversationId: String, botName: String, jobName: String, toolName: String) {
        post(
            context,
            conversationId,
            context.getString(R.string.bot_routine_notify_approval_title, botName),
            context.getString(R.string.bot_routine_notify_approval_text, jobName, toolName),
        )
    }

    fun notifyExpired(context: Context, conversationId: String, botName: String, jobName: String) {
        post(
            context,
            conversationId,
            context.getString(R.string.bot_routine_notify_expired_title, botName),
            context.getString(R.string.bot_routine_notify_expired_text, jobName),
        )
    }
}
