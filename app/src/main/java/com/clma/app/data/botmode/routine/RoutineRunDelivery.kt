package com.clma.app.data.botmode.routine

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.clma.app.service.CronJobWorker
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal object RoutineRunDelivery {
    const val CATCHUP = "bot_catchup"
    const val QUEUED = "bot_queued_slot"
    const val QUOTA = "bot_quota_run"

    fun queued(context: Context, jobId: String, slot: Long) =
        enqueue(context, jobId, QUEUED, slot, 0L, "slot_$slot")

    fun quota(context: Context, jobId: String, retry: QuotaRetry) =
        enqueue(context, jobId, QUOTA, retry.runId, retry.atMs - System.currentTimeMillis(), retry.runId)

    private fun enqueue(context: Context, jobId: String, key: String, value: Any, delayMs: Long, suffix: String) {
        val request = OneTimeWorkRequestBuilder<CronJobWorker>()
            .setInputData(workDataOf(CronJobWorker.KEY_JOB_ID to jobId, key to value))
            .setInitialDelay(delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("bot_routine_${jobId}_$suffix", ExistingWorkPolicy.KEEP, request)
    }

    suspend fun recoverOnBoot(context: Context, store: RoutineStore) {
        runCatching { CheckBackDelivery.recover(context, store) }.onFailure {
            currentCoroutineContext().ensureActive()
            Log.w("BotRoutineDelivery", "Check-back recovery failed", it)
        }
        runCatching {
            store.read().routines.forEach { (id, state) ->
                state.queuedSlot?.let { queued(context, id, it) }
                state.quota?.let { quota(context, id, it) }
            }
        }.onFailure {
            currentCoroutineContext().ensureActive()
            Log.w("BotRoutineDelivery", "Routine recovery failed", it)
        }
    }
}
