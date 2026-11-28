// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), server/routines.ts, server/routine-same-conversation.ts, docs/routine-schedules.md,
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0
// (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.data.botmode.routine

import com.clma.ai.ui.UIMessagePart
import com.clma.app.data.botmode.BotRoutineApprovalRules
import com.clma.app.data.db.entity.ScheduledJobEntity
import com.clma.app.data.model.Conversation
import com.clma.app.data.repository.ConversationRepository
import com.clma.app.data.repository.ScheduledJobRepository
import com.clma.app.service.ChatService
import com.clma.app.service.CronJobWorker.Companion.OUTCOME_AWAITING_APPROVAL
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeoutOrNull

internal class BotRoutineTurn(
    private val store: RoutineStore,
    private val jobs: ScheduledJobRepository,
    private val conversationRepo: ConversationRepository,
    private val chatService: ChatService,
    private val deliveryDirective: String,
) {
    suspend fun run(
        job: ScheduledJobEntity,
        state: RoutineState,
        quotaRetry: QuotaRetry?,
        retryConversationId: String?,
        runId: String,
        manual: Boolean,
    ): Triple<String, String?, Uuid?> {
        val assistantId = Uuid.parse(job.assistantId)
        val prompt = job.prompt ?: return Triple("failed", "missing_prompt_for_llm_mode", null)
        val options = state.options
        val gated = options.changedUrl.isNotBlank() || options.condition.isNotBlank()
        val now = System.currentTimeMillis()
        if (gated && !manual && quotaRetry == null && !state.checkPacing.allows(now)) {
            return Triple("skipped", "check_paced", null)
        }
        suspend fun settleCheck(failed: Boolean) {
            if (gated && !manual) store.editExisting(job.id) { current ->
                if (current.options == options) {
                    current.copy(checkPacing = current.checkPacing.settle(System.currentTimeMillis(), failed, runId))
                } else current
            }
        }
        var hash = quotaRetry?.pageHash?.takeIf { quotaRetry.changedUrl == options.changedUrl }
        if (quotaRetry == null) {
            try {
                if (options.changedUrl.isNotBlank()) {
                    hash = RoutineChecks.pageHash(options.changedUrl)
                    if (hash == state.pageHash) return Triple("skipped", "check_not_met", null)
                }
                if (options.condition.isNotBlank() && !chatService.botRoutineCondition(assistantId, options.condition)) {
                    return Triple("skipped", "check_not_met", null)
                }
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                currentCoroutineContext().ensureActive()
                settleCheck(failed = true)
                return Triple("skipped", "check_unavailable", null)
            }
            if (gated && !manual) store.editExisting(job.id) {
                if (it.options == options) it.copy(checkPacing = it.checkPacing.admit(now)) else it
            }
        }
        val reuse = retryConversationId ?: state.conversationId.takeIf { options.sameConversation }
        val saved = reuse?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        val conversation = saved?.let { conversationRepo.getConversationById(it) }
            ?.takeIf { it.assistantId == assistantId }
            ?: Conversation.ofId(Uuid.random(), assistantId = assistantId, newConversation = true)
                .copy(title = "[Scheduled] ${job.name}")
                .also {
                    conversationRepo.insertConversation(it)
                    if (options.sameConversation) store.editExisting(job.id) { current ->
                        current.copy(conversationId = it.id.toString())
                    }
                }
        val convId = conversation.id
        suspend fun attempt(model: Uuid?): Pair<String, Throwable?> {
            val errorsBefore = chatService.errors.value.filter { it.conversationId == convId }.mapTo(mutableSetOf()) { it.id }
            val messageId = Uuid.random()
            val generation = chatService.startBotScheduledTurn(
                convId, assistantId, messageId, deliveryDirective + "\n\n" + prompt, model, parkable = true,
            ) ?: return "skipped" to IllegalStateException("conversation_busy")
            try {
                val completed = withTimeoutOrNull(BotRoutineApprovalRules.RUN_LIMIT_MS) {
                    generation.join()
                    true
                }
                if (completed != true) return "timed_out" to null
            } finally {
                if (generation.isActive) generation.cancel()
            }
            val messages = chatService.getConversationFlow(convId).value.currentMessages
            val attemptMessages = messages.dropWhile { it.id != messageId }
            if (attemptMessages.any { message -> message.parts.any { it is UIMessagePart.Tool && it.isPending } }) {
                return OUTCOME_AWAITING_APPROVAL to null
            }
            val error = chatService.errors.value.lastOrNull {
                it.conversationId == convId && it.id !in errorsBefore
            }?.error ?: return (if (generation.isCancelled || attemptMessages.isEmpty()) "failed" else "success") to null
            val sideEffectsPossible = attemptMessages.isEmpty() || attemptMessages.drop(1).any { message ->
                message.parts.any { it is UIMessagePart.Tool || it is UIMessagePart.Text && it.text.isNotBlank() }
            }
            return (if (sideEffectsPossible) "failed" else "retryable") to error
        }

        var (outcome, error) = attempt(null)
        val firstError = error
        if (outcome == "retryable" && firstError != null && RoutineChecks.quotaFailure(firstError)) {
            val backup = store.read().backupModels[job.assistantId]
                ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                ?.takeIf { chatService.canUseBotBackup(assistantId, convId, it) }
            if (backup != null) {
                val next = attempt(backup)
                outcome = next.first
                error = next.second
            }
            if (outcome == "retryable" && quotaRetry == null && error?.let(RoutineChecks::quotaFailure) == true) {
                val at = System.currentTimeMillis()
                val reset = error?.let { RoutineChecks.resetAt(it, at) } ?: RoutineChecks.resetAt(firstError, at)
                if (reset != null && (job.endAtUnixMs == null || reset <= job.endAtUnixMs)) {
                    if (jobs.getById(job.id)?.enabled == true) {
                        store.edit(job.id) { it.copy(quota = QuotaRetry(runId, reset, hash, options.changedUrl, manual)) }
                        if (jobs.getById(job.id) != null) return Triple("waiting_quota", reset.toString(), convId)
                        store.delete(job.id)
                    }
                }
            }
        }
        if (outcome == "success" || outcome == OUTCOME_AWAITING_APPROVAL) {
            hash?.let { next -> store.editExisting(job.id) {
                if (it.options.changedUrl == options.changedUrl) it.copy(pageHash = next) else it
            } }
        }
        if (outcome != "skipped") settleCheck(failed = outcome != "success" && outcome != OUTCOME_AWAITING_APPROVAL)
        return Triple(if (outcome == "retryable") "failed" else outcome, error?.message, convId)
    }
}
