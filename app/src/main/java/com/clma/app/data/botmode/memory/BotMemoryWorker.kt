// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), server/memory-upkeep.ts,
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0
// (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.data.botmode.memory

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.clma.app.data.datastore.SettingsStore
import com.clma.app.data.model.isBot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.uuid.Uuid
import java.util.concurrent.TimeUnit

class BotMemoryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(BOT_ID)?.let(Uuid::parse) ?: return Result.failure()
        return try {
            BotMemoryOrganizer.run(applicationContext, id, automatic = true)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("BotMemoryWorker", "Memory organization failed (${e.javaClass.simpleName})")
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val BOT_ID = "bot_id"

        internal suspend fun observeBots(context: Context, settingsStore: SettingsStore) {
            var previous = emptySet<Uuid>()
            settingsStore.settingsFlowRaw
                .map { s -> s.assistants.filter { it.isBot }.mapTo(mutableSetOf()) { it.id } }
                .distinctUntilChanged()
                .catch { e -> Log.w("BotMemoryWorker", "Could not observe bots (${e.javaClass.simpleName})") }
                .collect { ids ->
                    for (id in (previous - ids) + (ids - previous)) {
                        try {
                            val enabled = id in ids && BotMemoryStore(context.filesDir, id.toString()).upkeepEnabled()
                            schedule(context, id, enabled)
                        } catch (e: Exception) {
                            Log.w("BotMemoryWorker", "Could not schedule memory organization (${e.javaClass.simpleName})")
                        }
                    }
                    previous = ids
                }
        }

        internal fun schedule(context: Context, id: Uuid, enabled: Boolean) {
            val manager = WorkManager.getInstance(context)
            val name = "bot-memory-organize-$id"
            if (!enabled) {
                manager.cancelUniqueWork(name)
                return
            }
            val work = PeriodicWorkRequestBuilder<BotMemoryWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresDeviceIdle(true).build())
                .setInputData(workDataOf(BOT_ID to id.toString()))
                .build()
            manager.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.KEEP, work)
        }
    }
}
