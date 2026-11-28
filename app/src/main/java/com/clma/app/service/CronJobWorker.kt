package com.clma.app.service

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

class CronJobWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(notificationId, scheduledRunNotification(applicationContext))

    override suspend fun doWork(): Result {
        // Expedited work uses a foreground service on older Android versions.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            runCatching { setForeground(getForegroundInfo()) }.onFailure {
                coroutineContext.ensureActive()
                Log.w("CronJobWorker", "Unable to promote scheduled work", it)
            }
        }
        return CronJobExecution(applicationContext, fromWorker = true).execute(inputData, workId = id.toString())
    }

    private val notificationId = (id.hashCode() and 0x3FFFFFFF) or 0x40000000

    companion object {
        const val KEY_JOB_ID = "cron_job_id"
        const val KEY_MANUAL = "cron_job_manual"
        const val KEY_SCHEDULED_AT_MS = "cron_job_scheduled_at_ms"
        /** Bot routines: resume the parked run of this conversation id. */
        const val KEY_RESUME_CONVERSATION = "bot_routine_resume_conversation"
        /** Bot routines: give up on the parked run of this conversation id. */
        const val KEY_EXPIRE_CONVERSATION = "bot_routine_expire_conversation"
        /** Run-row outcome while a Bot routine waits on the user's approval. */
        const val OUTCOME_AWAITING_APPROVAL = "awaiting_approval"
        /** Run-row outcome when nobody answered a parked run in time. */
        const val OUTCOME_APPROVAL_EXPIRED = "approval_expired"
        const val CHANNEL_ID = "rikkahub_cron_jobs"
    }
}
