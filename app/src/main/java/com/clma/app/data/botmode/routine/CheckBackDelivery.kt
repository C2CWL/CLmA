// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), server/host/check-backs.ts, server/routes/check-backs.ts,
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0
// (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.data.botmode.routine

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.clma.app.R
import com.clma.app.data.datastore.SettingsStore
import com.clma.app.data.model.isBot
import com.clma.app.data.repository.ConversationRepository
import com.clma.app.service.ChatService
import com.clma.app.service.scheduledRunNotification
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

internal fun routineZone(timezone: String?): ZoneId =
    timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()

object CheckBackDelivery {
    private const val ID = "check_back_id"

    private fun pending(context: Context, id: String): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, CheckBackAlarmReceiver::class.java).setData(Uri.parse("clma://check-back/$id")),
        PendingIntent.FLAG_IMMUTABLE,
    )

    fun schedule(context: Context, checkBack: CheckBack) {
        val delay = checkBack.atMs - System.currentTimeMillis()
        if (delay <= 0) {
            enqueue(context, checkBack.id)
            return
        }
        val alarm = context.getSystemService(AlarmManager::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarm.canScheduleExactAlarms()) {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, checkBack.atMs, pending(context, checkBack.id))
            } else {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, checkBack.atMs, pending(context, checkBack.id))
            }
        } catch (_: SecurityException) {
            enqueue(context, checkBack.id, delay)
        }
    }

    fun enqueue(context: Context, id: String, delay: Long = 0) {
        val request = OneTimeWorkRequestBuilder<CheckBackWorker>()
            .setInputData(workDataOf(ID to id))
            .apply {
                if (delay > 0) setInitialDelay(delay, TimeUnit.MILLISECONDS)
                else setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("check_back_$id", ExistingWorkPolicy.KEEP, request)
    }

    suspend fun cancel(context: Context, store: RoutineStore, id: String, conversationId: String): Boolean {
        var removed = false
        store.update {
            if (it.checkBacks[id]?.conversationId != conversationId) return@update it
            removed = true
            it.copy(checkBacks = it.checkBacks - id)
        }
        if (removed) context.getSystemService(AlarmManager::class.java).cancel(pending(context, id))
        return removed
    }

    suspend fun recover(context: Context, store: RoutineStore) {
        store.read().checkBacks.values.forEach { schedule(context, it) }
    }

    internal fun workerId(worker: CheckBackWorker): String? = worker.inputData.getString(ID)
}

class CheckBackAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.data?.lastPathSegment?.let { CheckBackDelivery.enqueue(context, it) }
    }
}

class CheckBackWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    private val store: RoutineStore by inject()
    private val settings: SettingsStore by inject()
    private val conversations: ConversationRepository by inject()
    private val chat: ChatService by inject()

    override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo((id.hashCode() and 0x3FFFFFFF) or 0x20000000, scheduledRunNotification(applicationContext))

    override suspend fun doWork(): Result {
        val id = CheckBackDelivery.workerId(this) ?: return Result.failure()
        val item = store.read().checkBacks[id] ?: return Result.success()
        if (item.atMs > System.currentTimeMillis()) return Result.retry()
        val messageId = Uuid.parse(id)
        val conversationId = Uuid.parse(item.conversationId)
        val assistantId = Uuid.parse(item.assistantId)
        val conversation = conversations.getConversationById(conversationId)
        val assistants = settings.settingsFlow.first { !it.init }.assistants
        if (conversation != null && conversation.assistantId == assistantId && !conversation.isTemporary &&
            assistants.any { it.id == assistantId && it.isBot } &&
            conversation.messageNodes.none { node -> node.messages.any { it.id == messageId } }
        ) {
            val prompt = applicationContext.getString(R.string.routine2_check_back_turn, item.note) +
                item.condition.takeIf { it.isNotBlank() }?.let {
                    "\n" + applicationContext.getString(R.string.routine2_check_back_gate, it)
                }.orEmpty()
            if (store.read().checkBacks[id] == null) return Result.success()
            val generation = chat.startBotScheduledTurn(conversationId, assistantId, messageId, prompt)
                ?: return Result.retry()
            try {
                withTimeoutOrNull(8 * 60_000L) { generation.join() }
            } finally {
                if (generation.isActive) generation.cancel()
            }
            if (conversations.getConversationById(conversationId)?.messageNodes
                    ?.none { node -> node.messages.any { it.id == messageId } } == true
            ) return Result.retry()
        }
        CheckBackDelivery.cancel(applicationContext, store, id, item.conversationId)
        return Result.success()
    }
}
