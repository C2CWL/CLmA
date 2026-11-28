// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), server/routine-recovery.ts,
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0
// (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid
import org.koin.java.KoinJavaComponent

class BotQuotaResumeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val conversationId = inputData.getString(CONVERSATION_ID)?.let(Uuid::parse) ?: return Result.failure()
        val chatService = KoinJavaComponent.getKoin().get<ChatService>()
        return try {
            if (chatService.resumeBotQuotaWait(conversationId, id.toString(), automatic = true)) {
                Result.success()
            } else {
                Result.retry()
            }
        } catch (_: IOException) {
            Result.retry()
        }
    }

    companion object {
        private const val CONVERSATION_ID = "conversation_id"

        internal fun request(conversationId: Uuid, retryAt: Long) =
            OneTimeWorkRequestBuilder<BotQuotaResumeWorker>()
                .setInputData(workDataOf(CONVERSATION_ID to conversationId.toString()))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay((retryAt - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .build()
    }
}
