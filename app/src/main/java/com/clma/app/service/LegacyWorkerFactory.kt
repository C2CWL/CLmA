package com.clma.app.service

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.clma.app.workflow.trigger.WorkflowTimeCronWorker

/**
 * WorkManager persists every enqueued request with its worker's fully-qualified class name.
 * Work queued by a build from before the me.rerere.rikkahub -> com.clma.app package move
 * still carries the old names, which the default reflective factory can no longer load (the
 * work would fail with ClassNotFoundException and never run again).
 *
 * This factory maps those old names onto the moved classes so already-queued work keeps
 * running until it is naturally re-enqueued under the new name (cron jobs are re-armed with
 * REPLACE on boot / package replace, workflow timers with REPLACE at startup, the Telegram
 * health probe with UPDATE). It returns null for every other name so the next factory /
 * WorkManager's default reflection handles it.
 */
class LegacyWorkerFactory : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (LEGACY_WORKER_CLASS_NAMES[workerClassName]) {
        CronJobWorker::class.java.name -> CronJobWorker(appContext, workerParameters)
        TelegramBotHealthWorker::class.java.name -> TelegramBotHealthWorker(appContext, workerParameters)
        WorkflowTimeCronWorker::class.java.name -> WorkflowTimeCronWorker(appContext, workerParameters)
        else -> null
    }

    companion object {
        /** Old (pre-rename) worker FQCN -> current worker FQCN. Strings on purpose. */
        val LEGACY_WORKER_CLASS_NAMES: Map<String, String> = mapOf(
            "me.rerere.rikkahub.service.CronJobWorker" to
                CronJobWorker::class.java.name,
            "me.rerere.rikkahub.service.TelegramBotHealthWorker" to
                TelegramBotHealthWorker::class.java.name,
            "me.rerere.rikkahub.workflow.trigger.WorkflowTimeCronWorker" to
                WorkflowTimeCronWorker::class.java.name,
        )
    }
}
