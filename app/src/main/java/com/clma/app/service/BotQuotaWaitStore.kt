// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), server/limit-resets.ts, server/routine-recovery.ts,
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0
// (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.service

import android.content.Context
import android.util.Log
import androidx.work.WorkManager
import androidx.work.await
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.clma.app.data.botmode.BotQuotaLimit
import com.clma.app.data.model.Conversation
import com.clma.app.utils.JsonInstant
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

private val Context.quotaWaitDataStore by preferencesDataStore(name = "bot_quota_waits")

@Serializable
internal data class BotQuotaWait(
    val id: String,
    val botId: String,
    val messageId: String,
    val historyFingerprint: String,
    val resetAt: Long?,
    val retryAt: Long?,
) {
    fun matches(conversation: Conversation): Boolean =
        botId == conversation.assistantId.toString() &&
            messageId == conversation.currentMessages.lastOrNull()?.id.toString() &&
            historyFingerprint == conversation.quotaHistoryFingerprint()
}

internal fun Conversation.quotaHistoryFingerprint(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    currentMessages.forEach { message ->
        digest.update(message.id.toString().encodeToByteArray())
        digest.update(JsonInstant.encodeToString(message.parts).encodeToByteArray())
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal class BotQuotaWaitStore(private val context: Context) {
    private val dataStore = context.applicationContext.quotaWaitDataStore

    fun observe(conversationId: Uuid) = dataStore.data.map {
        it[key(conversationId)]?.let { value -> Json.decodeFromString<BotQuotaWait>(value) }
    }.distinctUntilChanged()

    suspend fun get(conversationId: Uuid) = observe(conversationId).first()

    suspend fun park(conversation: Conversation, limit: BotQuotaLimit, allowAutomaticRetry: Boolean) {
        val message = conversation.currentMessages.last()
        val retryAt = if (allowAutomaticRetry && !conversation.isTemporary) {
            limit.automaticRetryAt(System.currentTimeMillis())
        } else null
        val fingerprint = conversation.quotaHistoryFingerprint()
        val request = retryAt?.let { BotQuotaResumeWorker.request(conversation.id, it) }
        val wait = BotQuotaWait(
            id = request?.id?.toString() ?: UUID.randomUUID().toString(),
            botId = conversation.assistantId.toString(),
            messageId = message.id.toString(),
            historyFingerprint = fingerprint,
            resetAt = limit.resetAt,
            retryAt = retryAt,
        )
        // Commit the work before showing the card. Enqueue failure leaves a manual retry.
        val scheduled = request != null && runCatching {
            WorkManager.getInstance(context).enqueue(request).await()
        }.onFailure {
            if (it is CancellationException) throw it
            Log.w("BotQuotaWaitStore", "Could not schedule Bot quota resume", it)
        }.isSuccess
        dataStore.edit {
            it[key(conversation.id)] = Json.encodeToString(if (scheduled) wait else wait.copy(retryAt = null))
        }
    }

    suspend fun cancel(conversationId: Uuid, id: String? = null) {
        val waitId = id ?: get(conversationId)?.id ?: return
        remove(conversationId, waitId)?.let(::cancelWork)
    }

    fun cancelWork(wait: BotQuotaWait) {
        if (wait.retryAt != null) {
            WorkManager.getInstance(context).cancelWorkById(UUID.fromString(wait.id))
        }
    }

    suspend fun remove(conversationId: Uuid, id: String): BotQuotaWait? {
        var removed: BotQuotaWait? = null
        dataStore.edit {
            val key = key(conversationId)
            val current = it[key]?.let { value -> Json.decodeFromString<BotQuotaWait>(value) }
            if (current?.id == id) {
                it.remove(key)
                removed = current
            }
        }
        return removed
    }

    suspend fun cancelForBots(botIds: Set<String>?) {
        val removed = mutableListOf<BotQuotaWait>()
        dataStore.edit { preferences ->
            preferences.asMap().toList().forEach { (key, value) ->
                val wait = Json.decodeFromString<BotQuotaWait>(value as String)
                if (botIds == null || wait.botId in botIds) {
                    preferences.remove(stringPreferencesKey(key.name))
                    removed += wait
                }
            }
        }
        removed.forEach(::cancelWork)
    }

    private fun key(id: Uuid) = stringPreferencesKey(id.toString())
}
