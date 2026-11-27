package com.clma.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.clma.ai.ui.UIMessagePart
import com.clma.app.data.agentrun.AgentRunKind
import com.clma.app.data.botmode.BotRoutineApprovalRules
import com.clma.app.data.botmode.BotRoutineApprovals
import com.clma.app.data.botmode.ParkedRoutineRun
import com.clma.app.data.model.isBot
import com.clma.ai.core.MessageRole
import com.clma.app.data.agentrun.AgentRunRepository
import com.clma.app.data.agentrun.AgentRunStatus
import com.clma.app.data.ai.tools.HeadlessConversations
import com.clma.app.data.ai.AssistantResolver
import com.clma.app.data.ai.ToolSurfaceResolver
import com.clma.app.data.ai.tools.LocalTools
import com.clma.app.data.datastore.SettingsStore
import com.clma.app.data.db.entity.ScheduledJobEntity
import com.clma.app.data.db.entity.ScheduledJobRunEntity
import com.clma.app.data.model.Conversation
import com.clma.app.data.repository.ConversationRepository
import com.clma.app.data.repository.ScheduledJobRepository
import com.clma.app.data.repository.ScheduledJobRunRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private const val TAG = "CronJobWorker"

/** Outer bound on how long after [startedAtMs] a WorkManager replay can plausibly arrive. */
internal const val REPLAY_WINDOW_MS = 10L * 60_000L

/**
 * Prepended to every cron LLM-mode prompt. Models otherwise treat a `post_notification`
 * call as "done" and leave a terse chat reply, but the reply IS what the user reads when
 * they tap that notification (it deep-links back to this conversation), so it has to
 * carry the full result, not the notification.
 */
private const val CRON_DELIVERY_DIRECTIVE = "[System] Your full text reply to this task is saved to a conversation the user can open in the app, including by tapping any notification you post (the notification deep-links to this conversation). ALWAYS write your complete result as your reply here. If you also call post_notification, keep the notification a short alert and still include the full content in your reply. Do not rely on writing files or external channels for the user to read the result: your reply in this conversation is the record."

/**
 * Wait for the ChatService generation job on [flow] to terminate (transition to null)
 * within a wall-clock [timeoutMs] cap. Returns `true` on natural completion, `false` if
 * the cap fired first.
 *
 * The `Unit` sentinel is load-bearing: `.first { it == null }` returns the matched value
 * (null), and `withTimeoutOrNull` also returns null on timeout. Without the sentinel the
 * two outcomes are indistinguishable — every successful LLM-mode cron run was
 * misclassified as `timed_out` until this fix. (SubAgentEngine carries the same fix
 * inline; this helper exists so a JVM unit test can pin the contract.)
 */
internal suspend fun awaitGenerationTerminal(
    flow: Flow<Job?>,
    timeoutMs: Long,
): Boolean {
    val completed: Unit? = withTimeoutOrNull(timeoutMs) {
        flow.first { it == null }
        Unit
    }
    return completed != null
}

/**
 * Turn an [awaitGenerationTerminal] result into the LLM-mode run outcome, stopping the
 * still-running generation on timeout via [stop]. Left uncalled, a timed-out generation
 * keeps burning tokens indefinitely, and a later fire of the same job can start a second
 * parallel generation while the first is still going. [stop] is responsible for its own
 * failure handling (see the call site in [CronJobWorker.runLlm]) — this function only
 * guarantees it is invoked before the timeout is reported. Split out (like
 * [awaitGenerationTerminal]) so a JVM test can pin the stop-on-timeout contract without a
 * live ChatService.
 */
internal suspend fun finishRunLlm(
    completed: Boolean,
    convId: Uuid,
    stop: suspend () -> Unit,
): Triple<String, String?, Uuid?> {
    if (!completed) {
        stop()
        return Triple("timed_out", "llm turn exceeded 15min", convId)
    }
    return Triple("success", null, convId)
}

/**
 * The slot to stamp into the run row. Manual fires (trigger_job_now) happen at nowMs and
 * are NOT bound to any scheduled slot — stamping them with [jobNextRunAtMs] would poison
 * the next regular fire's replay guard. Natural fires stamp the slot they were enqueued
 * for so a true WorkManager replay still matches. Prefer the immutable work input:
 * job.nextRunAtMs may already point at the following fire when this request is replayed.
 */
internal fun computeRunSlot(
    isManual: Boolean,
    jobNextRunAtMs: Long?,
    nowMs: Long,
    enqueuedSlotMs: Long? = null,
): Long = if (isManual) nowMs else (enqueuedSlotMs ?: jobNextRunAtMs ?: nowMs)

/**
 * A WorkManager replay re-fires the SAME enqueued work request, so a true replay's
 * [slotMs] matches the prior row's exactly AND its [nowMs] is within minutes of the prior
 * row's [ScheduledJobRunEntity.startedAtMs] for legacy work without a frozen slot.
 * An explicit slot remains the same even after a long process restart, so it must not
 * expire after ten minutes. The time window only protects legacy, DB-derived slots.
 */
internal fun shouldSuppressAsReplay(
    priorRow: ScheduledJobRunEntity?,
    slotMs: Long,
    nowMs: Long,
    windowMs: Long = REPLAY_WINDOW_MS,
    slotIsExplicit: Boolean = false,
): Boolean {
    if (priorRow == null) return false
    if (priorRow.scheduledAtMs != slotMs) return false
    if (priorRow.outcome == "concurrent_skip" || priorRow.outcome == "skipped_catchup") return false
    return slotIsExplicit || priorRow.startedAtMs >= nowMs - windowMs
}

/**
 * Ceiling on how many run-history rows a single job's max_runs can force [trim] to retain.
 * schedule_job only validates max_runs >= 1 (ScheduleJobValidator), so without this cap
 * historyRetentionFor scales with an unbounded max_runs and trim(keep=...) would retain the
 * job's run history essentially forever — the same class of unbounded-row-growth bug
 * [CatchupPlanner.SKIPPED_ROWS_CAP] bounds on the catchup side.
 */
internal const val MAX_HISTORY_RETENTION = 1000

/**
 * How many run-history rows [ScheduledJobRunDao.trim] must retain for max_runs enforcement
 * to stay correct. boundsExpired() derives the job's progress from
 * [ScheduledJobRunRepository.countSuccessful], which only sees rows that survived trim() —
 * trimming below [maxRuns] silently caps the achievable success count at the trim floor, so
 * a job configured for e.g. 150 runs would fire forever once history reaches 100 rows.
 */
internal fun historyRetentionFor(maxRuns: Int?, baseline: Int = 100): Int =
    maxOf(baseline, maxRuns ?: 0).coerceAtMost(MAX_HISTORY_RETENTION)

/**
 * Tracks which jobs currently have a worker actively running. Prevents two
 * concurrent fires from racing on the same job — the second one writes a
 * 'concurrent_skip' history row and returns immediately.
 */
private object CronJobRunningTracker {
    private val running = ConcurrentHashMap.newKeySet<String>()
    fun start(jobId: String): Boolean = running.add(jobId)
    fun stop(jobId: String) { running.remove(jobId) }
}

class CronJobWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    private val repo: ScheduledJobRepository by inject()
    private val runRepo: ScheduledJobRunRepository by inject()
    private val scheduler: CronJobScheduler by inject()
    private val chatService: ChatService by inject()
    private val conversationRepo: ConversationRepository by inject()
    private val settingsStore: SettingsStore by inject()
    private val localTools: LocalTools by inject()
    private val directRunner: DirectModeActionRunner by inject()
    private val agentRunRepo: AgentRunRepository by inject()

    override suspend fun doWork(): Result {
        // Bot routines: the same worker resumes a parked run once its cards are answered, and
        // gives up on one nobody answered.
        inputData.getString(KEY_RESUME_CONVERSATION)?.let { return resumeParked(it) }
        inputData.getString(KEY_EXPIRE_CONVERSATION)?.let { return expireParked(it) }

        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val isManual = inputData.getBoolean(KEY_MANUAL, false)
        val enqueuedSlotMs = inputData.keyValueMap[KEY_SCHEDULED_AT_MS] as? Long

        if (!CronJobRunningTracker.start(jobId)) {
            recordRun(jobId, scheduledAtMs = System.currentTimeMillis(),
                outcome = "concurrent_skip", mode = "?", convId = null, errorMessage = null)
            return Result.success()
        }

        val nowMs = System.currentTimeMillis()
        val runRowId = Uuid.random().toString()

        try {
            val job = repo.getById(jobId) ?: return Result.success()
            if (!job.enabled) return Result.success()

            // Bounds re-check (defends against a stale enqueue surviving a job edit).
            if (boundsExpired(job, nowMs)) {
                repo.update(job.copy(enabled = false))
                return Result.success()
            }

            val scheduledAtMs = computeRunSlot(isManual, job.nextRunAtMs, nowMs, enqueuedSlotMs)

            // Replay guard runs BEFORE the optimistic insert, else the insert self-matches.
            // Manual fires skip the guard so the user can always force a fresh fire.
            if (!isManual) {
                val priorRow = runRepo.getForSlot(jobId, scheduledAtMs)
                if (priorRow != null && shouldSuppressAsReplay(
                        priorRow, scheduledAtMs, nowMs, slotIsExplicit = enqueuedSlotMs != null,
                    )) {
                    Log.w(TAG, "doWork: suppressing likely WorkManager replay for $jobId " +
                            "(prior row ${priorRow.id} same scheduledAtMs=$scheduledAtMs outcome=${priorRow.outcome})")
                    if (priorRow.finishedAtMs == null) {
                        runRepo.update(priorRow.copy(
                            finishedAtMs = nowMs,
                            outcome = "process_killed_replay",
                            errorMessage = "worker terminated mid-execute",
                        ))
                    }
                    runRepo.insert(ScheduledJobRunEntity(
                        id = runRowId,
                        jobId = jobId,
                        mode = job.mode,
                        scheduledAtMs = scheduledAtMs,
                        startedAtMs = nowMs,
                        finishedAtMs = nowMs,
                        outcome = "process_killed_replay",
                        conversationId = null,
                        errorMessage = "duplicate fire suppressed (prior run ${priorRow.id})",
                    ))
                    // Recover a process death between executing the task and rearming it.
                    // If the cursor already advanced, leave that successor alone.
                    if (job.nextRunAtMs == scheduledAtMs) {
                        val priorOutcome = if (priorRow.finishedAtMs == null) "process_killed_replay" else priorRow.outcome
                        advanceSchedule(job, priorRow.startedAtMs, priorOutcome)
                    }
                    runRepo.trim(jobId, keep = historyRetentionFor(job.maxRuns))
                    return Result.success()
                }
            }

            // Optimistic insert — outcome will be overwritten on completion.
            runRepo.insert(ScheduledJobRunEntity(
                id = runRowId,
                jobId = jobId,
                mode = job.mode,
                scheduledAtMs = scheduledAtMs,
                startedAtMs = nowMs,
                finishedAtMs = null,
                outcome = "success",                          // optimistic
                conversationId = null,
                errorMessage = null,
            ))

            // Phase 24 — open the cross-pillar ledger row alongside the domain detail row.
            // domain_id is keyed per-fire (jobId:slot) so a replay or a later fire of the
            // same job is a distinct ledger row. Best-effort: ledger failures never break the
            // cron run.
            val ledgerId = agentRunRepo.open(
                kind = AgentRunKind.Cron,
                domainId = "$jobId:$scheduledAtMs",
                metadata = buildJsonObject {
                    put("job_name", job.name)
                    put("mode", job.mode)
                    put("manual", isManual)
                },
            )

            // Bot-library owners get parkable runs: approvals wait for the user instead of
            // being refused, and every finished run posts a short notification.
            val botRoutine = job.mode == "llm" && isBotJob(job)
            val (outcome, errorMessage, convIdMaybe) = when (job.mode) {
                "llm"    -> runLlm(job, parkable = botRoutine)
                "direct" -> runDirect(job)
                else     -> Triple("failed", "unknown_mode:${job.mode}", null)
            }

            runRepo.update(ScheduledJobRunEntity(
                id = runRowId,
                jobId = jobId,
                mode = job.mode,
                scheduledAtMs = scheduledAtMs,
                startedAtMs = nowMs,
                finishedAtMs = System.currentTimeMillis(),
                outcome = outcome,
                conversationId = convIdMaybe?.toString(),
                errorMessage = errorMessage?.take(500),
            ))

            // Phase 24 — mirror the terminal outcome into the cross-pillar ledger. Cron
            // outcomes map to: success → succeeded; timed_out / failed / unknown → failed.
            // (concurrent_skip / skipped_catchup / process_killed_replay never reach here —
            // they early-return before the ledger row is opened.)
            if (outcome == OUTCOME_AWAITING_APPROVAL && convIdMaybe != null) {
                // Parked: the row stays `awaiting_approval` (finished, so the stranded-run sweep
                // leaves it alone) until the resume worker writes the real outcome.
                agentRunRepo.setStatus(ledgerId, AgentRunStatus.awaiting_approval)
                park(
                    ParkedRoutineRun(
                        conversationId = convIdMaybe.toString(),
                        assistantId = job.assistantId,
                        jobId = job.id,
                        jobName = job.name,
                        runId = runRowId,
                        mode = job.mode,
                        scheduledAtMs = scheduledAtMs,
                        startedAtMs = nowMs,
                        ledgerId = ledgerId,
                        workedMs = System.currentTimeMillis() - nowMs,
                        parkedAtMs = 0L,
                        expiresAtMs = 0L,
                    )
                )
            } else {
                agentRunRepo.markTerminal(
                    id = ledgerId,
                    status = if (outcome == "success") AgentRunStatus.succeeded else AgentRunStatus.failed,
                    lastError = if (outcome == "success") null else "$outcome: ${errorMessage.orEmpty()}",
                )

                if (botRoutine && convIdMaybe != null) {
                    notifyBotRoutineFinished(job.assistantId, job.name, convIdMaybe, outcome, errorMessage)
                } else if (outcome != "success") {
                    // Failure notification (post once per failure; the existing channel handles dedup)
                    postFailureNotification(
                        job.id, job.name, "$outcome: ${errorMessage.orEmpty()}", convIdMaybe,
                    )
                }
            }

            // Manual fires (trigger_job_now) are bonus — they get a history row but
            // don't bump runs_so_far or lastRunAtMs (per spec Decision 13). The regular
            // schedule continues unaffected.
            if (!isManual) {
                advanceSchedule(job, nowMs, outcome)
            }

            // Trim history at the end so this row's insert/update is reflected in the cap.
            runRepo.trim(jobId, keep = historyRetentionFor(job.maxRuns))

            return Result.success()
        } finally {
            CronJobRunningTracker.stop(jobId)
        }
    }

    private suspend fun advanceSchedule(job: ScheduledJobEntity, startedAtMs: Long, outcome: String) {
        val runsSoFar = if (outcome == "success") runRepo.countSuccessful(job.id) else job.runsSoFar
        val maxReached = job.maxRuns != null && runsSoFar >= job.maxRuns
        val updated = job.copy(
            lastRunAtMs = startedAtMs,
            runsSoFar = runsSoFar,
            enabled = job.enabled && job.scheduleType != "once" && !maxReached,
        )
        repo.update(updated)
        if (updated.enabled) scheduler.schedule(updated, fromWorker = true)
    }

    /** Only completed successes consume max_runs; an optimistic in-flight row does not. */
    private suspend fun boundsExpired(job: ScheduledJobEntity, nowMs: Long): Boolean {
        if (job.endAtUnixMs != null && nowMs > job.endAtUnixMs) return true
        if (job.maxRuns != null) {
            val successCount = runRepo.countSuccessful(job.id)
            if (successCount >= job.maxRuns) return true
        }
        return false
    }

    private suspend fun runLlm(job: ScheduledJobEntity, parkable: Boolean = false): Triple<String, String?, Uuid?> {
        val prompt = job.prompt ?: return Triple("failed", "missing_prompt_for_llm_mode", null)
        val assistantUuid = runCatching { Uuid.parse(job.assistantId) }.getOrNull()
            ?: return Triple("failed", "bad_assistant_id:${job.assistantId}", null)

        val conv = Conversation.ofId(
            id = Uuid.random(),
            assistantId = assistantUuid,
            newConversation = true,
        ).copy(title = "[Scheduled] ${job.name}")
        conversationRepo.insertConversation(conv)
        chatService.initializeConversation(conv.id)
        if (parkable) HeadlessConversations.markParkable(conv.id) else HeadlessConversations.mark(conv.id)
        try {
            chatService.sendMessage(conv.id, listOf(UIMessagePart.Text(CRON_DELIVERY_DIRECTIVE + "\n\n" + prompt)))
            // Wait for the generation job to clear, with a 15-min wall-clock cap.
            // See awaitGenerationTerminal's KDoc for the Unit-sentinel rationale.
            val completed = awaitGenerationTerminal(
                flow = chatService.getGenerationJobStateFlow(conv.id),
                timeoutMs = BotRoutineApprovalRules.RUN_LIMIT_MS,
            )
            // A parkable run that stopped on an approval card is waiting, not finished.
            if (completed && parkable && pendingToolName(conv.id) != null) {
                return Triple(OUTCOME_AWAITING_APPROVAL, null, conv.id)
            }
            return finishRunLlm(completed, conv.id) {
                runCatching { chatService.stopGeneration(conv.id) }
                    .onFailure { Log.w(TAG, "runLlm: stopGeneration failed after timeout for ${job.id}", it) }
            }
        } catch (t: Throwable) {
            return Triple("failed", "${t::class.simpleName}: ${t.message.orEmpty()}", conv.id)
        } finally {
            HeadlessConversations.unmark(conv.id)
        }
    }

    private suspend fun runDirect(job: ScheduledJobEntity): Triple<String, String?, Uuid?> {
        // Replay idempotency guard: if WorkManager replays this worker after a process kill,
        // a run row for this job will already exist with a very recent startedAtMs.
        // Treat any non-skip row created within the last 5 minutes as a duplicate and bail.
        val actionsJson = job.actionsJson ?: return Triple("failed", "missing_actions_for_direct_mode", null)
        val parsed = DirectModeActionRunner.parse(actionsJson).getOrElse {
            return Triple("failed", "actions_parse:${(it as? DirectModeActionRunner.ParseError)?.code ?: it.message}", null)
        }
        // Tool list scoped to the job's assistant — same path ChatService uses.
        val assistantUuid = runCatching { Uuid.parse(job.assistantId) }.getOrNull()
            ?: return Triple("failed", "bad_assistant_id:${job.assistantId}", null)
        val settings = settingsStore.settingsFlow.first()
        val assistant = AssistantResolver.byId(settings, assistantUuid)
            ?: return Triple("failed", "assistant_not_found", null)
        // Headless context — sub-agent recursion guard fires from this dispatch path so
        // a cron job's direct-mode action sequence cannot itself spawn a sub-agent.
        val tools = ToolSurfaceResolver.resolve(
            localTools = localTools,
            assistant = assistant,
            context = ToolSurfaceResolver.headlessContext(assistantUuid),
        )
        val seq = directRunner.run(parsed, tools)
        return Triple(seq.finalOutcome, seq.errorMessage, null)
    }

    // ------------------------------------------------------------ Bot routines

    private suspend fun isBotJob(job: ScheduledJobEntity): Boolean {
        val id = runCatching { Uuid.parse(job.assistantId) }.getOrNull() ?: return false
        val settings = settingsStore.settingsFlow.first()
        return AssistantResolver.byId(settings, id)?.isBot == true
    }

    private suspend fun botName(assistantId: String): String {
        val id = runCatching { Uuid.parse(assistantId) }.getOrNull()
        val settings = settingsStore.settingsFlow.first()
        val name = id?.let { AssistantResolver.byId(settings, it)?.name }.orEmpty()
        return name.ifBlank { applicationContext.getString(com.clma.app.R.string.bot_routine_default_bot_name) }
    }

    /** Name of the first tool still waiting for approval in [convId], or null when none is. */
    private suspend fun pendingToolName(convId: Uuid): String? {
        runCatching { chatService.ensureHydrated(convId) }
        return chatService.getConversationFlow(convId).value.currentMessages
            .asSequence()
            .flatMap { it.parts.asSequence() }
            .filterIsInstance<UIMessagePart.Tool>()
            .firstOrNull { it.isPending }
            ?.toolName
    }

    /** First line of the run's last assistant reply, for the result notification. */
    private fun lastReplyLine(convId: Uuid): String =
        chatService.getConversationFlow(convId).value.currentMessages
            .lastOrNull { it.role == MessageRole.ASSISTANT && it.parts.any { p -> p is UIMessagePart.Text && p.text.isNotBlank() } }
            ?.parts?.filterIsInstance<UIMessagePart.Text>()
            ?.joinToString(" ") { it.text }
            ?.replace(Regex("[`*#>_~]+"), "")
            ?.trim()
            .orEmpty()

    private suspend fun notifyBotRoutineFinished(
        assistantId: String,
        jobName: String,
        convId: Uuid,
        outcome: String,
        errorMessage: String?,
    ) {
        val name = botName(assistantId)
        if (outcome == "success") {
            BotRoutineRuns.notifyDone(applicationContext, convId.toString(), name, jobName, lastReplyLine(convId))
        } else {
            BotRoutineRuns.notifyFailed(applicationContext, convId.toString(), name, jobName, "$outcome ${errorMessage.orEmpty()}".trim())
        }
    }

    /** Save a run that stopped on an approval card, arm its give-up timer and tell the user. */
    private suspend fun park(run: ParkedRoutineRun) {
        val now = System.currentTimeMillis()
        val parked = run.copy(
            parkedAtMs = now,
            expiresAtMs = BotRoutineApprovalRules.expiresAt(now),
            resuming = false,
        )
        BotRoutineApprovals.put(parked)
        BotRoutineRuns.scheduleExpiry(applicationContext, parked.conversationId, BotRoutineApprovalRules.APPROVAL_TTL_MS)
        val convId = Uuid.parse(parked.conversationId)
        val tool = pendingToolName(convId).orEmpty()
            .removePrefix("mcp__")
        BotRoutineRuns.notifyApprovalNeeded(
            applicationContext, parked.conversationId, botName(parked.assistantId), parked.jobName, tool,
        )
    }

    private suspend fun writeRunRow(run: ParkedRoutineRun, outcome: String, errorMessage: String?) {
        runCatching {
            runRepo.update(ScheduledJobRunEntity(
                id = run.runId,
                jobId = run.jobId,
                mode = run.mode,
                scheduledAtMs = run.scheduledAtMs,
                startedAtMs = run.startedAtMs,
                finishedAtMs = System.currentTimeMillis(),
                outcome = outcome,
                conversationId = run.conversationId,
                errorMessage = errorMessage?.take(500),
            ))
        }.onFailure { Log.w(TAG, "writeRunRow failed for ${run.runId}", it) }
    }

    /**
     * The user answered every approval card of a parked run: continue the same turn headless,
     * with only the run time it has left (time spent waiting is not counted).
     */
    private suspend fun resumeParked(conversationId: String): Result {
        val parkedRun = BotRoutineApprovals.get(conversationId) ?: return Result.success()
        val convId = runCatching { Uuid.parse(conversationId) }.getOrNull() ?: run {
            BotRoutineApprovals.remove(conversationId)
            return Result.success()
        }
        // The routine was deleted while this run waited: settle it, never execute it.
        if (repo.getById(parkedRun.jobId) == null) return expireParked(conversationId)
        // Another card is still open: wait for it (its answer re-queues this worker), or for
        // the deadline if it has already passed.
        if (pendingToolName(convId) != null) {
            return if (!parkedRun.resuming && BotRoutineApprovalRules.isExpired(parkedRun, System.currentTimeMillis())) {
                expireParked(conversationId)
            } else Result.success()
        }
        val trackerKey = "resume:$conversationId"
        if (!CronJobRunningTracker.start(trackerKey)) return Result.success()

        BotRoutineRuns.cancelExpiry(applicationContext, conversationId)
        BotRoutineApprovals.put(parkedRun.copy(resuming = true))
        parkedRun.ledgerId?.let { agentRunRepo.setStatus(it, AgentRunStatus.running) }
        val resumedAt = System.currentTimeMillis()
        val budgetMs = BotRoutineApprovalRules.remainingBudgetMs(parkedRun.workedMs)
        val errorsBefore = chatService.errors.value.filter { it.conversationId == convId }.mapTo(mutableSetOf()) { it.id }
        var outcome = "failed"
        var errorMessage: String? = null
        HeadlessConversations.markParkable(convId)
        try {
            if (budgetMs <= 0L) {
                outcome = "timed_out"
                errorMessage = "llm turn exceeded the run limit"
            } else {
                chatService.resumeAnsweredTurn(convId)
                val completed = awaitGenerationTerminal(
                    flow = chatService.getGenerationJobStateFlow(convId),
                    timeoutMs = budgetMs,
                )
                val newError = chatService.errors.value
                    .lastOrNull { it.conversationId == convId && it.id !in errorsBefore }
                outcome = when {
                    !completed -> {
                        runCatching { chatService.stopGeneration(convId) }
                        errorMessage = "llm turn exceeded the run limit"
                        "timed_out"
                    }
                    pendingToolName(convId) != null -> OUTCOME_AWAITING_APPROVAL
                    newError != null -> {
                        errorMessage = newError.error.message ?: newError.error::class.simpleName
                        "failed"
                    }
                    else -> "success"
                }
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) {
                // The worker was stopped mid-resume. The turn runs in the app scope, so stop it
                // first; tools may already have run, so never replay it: re-park only if it is
                // sitting on a new card, otherwise settle it as interrupted.
                withContext(NonCancellable) {
                    runCatching { chatService.stopGeneration(convId) }
                    val worked = parkedRun.workedMs + (System.currentTimeMillis() - resumedAt)
                    if (pendingToolName(convId) != null) {
                        writeRunRow(parkedRun, OUTCOME_AWAITING_APPROVAL, null)
                        parkedRun.ledgerId?.let { agentRunRepo.setStatus(it, AgentRunStatus.awaiting_approval) }
                        park(parkedRun.copy(workedMs = worked))
                    } else {
                        settle(parkedRun, convId, "failed", "interrupted while resuming", notify = true)
                    }
                }
                throw t
            }
            outcome = "failed"
            errorMessage = "${t::class.simpleName}: ${t.message.orEmpty()}"
        } finally {
            HeadlessConversations.unmark(convId)
            CronJobRunningTracker.stop(trackerKey)
        }

        val worked = parkedRun.workedMs + (System.currentTimeMillis() - resumedAt)
        if (outcome == OUTCOME_AWAITING_APPROVAL) {
            writeRunRow(parkedRun, OUTCOME_AWAITING_APPROVAL, null)
            parkedRun.ledgerId?.let { agentRunRepo.setStatus(it, AgentRunStatus.awaiting_approval) }
            park(parkedRun.copy(workedMs = worked))
            return Result.success()
        }
        settle(parkedRun, convId, outcome, errorMessage, notify = true)
        return Result.success()
    }

    /** Write a parked run's final outcome everywhere and drop it from the store. */
    private suspend fun settle(
        parkedRun: ParkedRoutineRun,
        convId: Uuid,
        outcome: String,
        errorMessage: String?,
        notify: Boolean,
    ) {
        BotRoutineApprovals.remove(parkedRun.conversationId)
        BotRoutineRuns.cancelExpiry(applicationContext, parkedRun.conversationId)
        writeRunRow(parkedRun, outcome, errorMessage)
        parkedRun.ledgerId?.let {
            agentRunRepo.markTerminal(
                id = it,
                status = if (outcome == "success") AgentRunStatus.succeeded else AgentRunStatus.failed,
                lastError = if (outcome == "success") null else "$outcome: ${errorMessage.orEmpty()}",
            )
        }
        if (notify) notifyBotRoutineFinished(parkedRun.assistantId, parkedRun.jobName, convId, outcome, errorMessage)
    }

    /**
     * Nobody answered a parked run in time, or its routine was deleted: close its cards, cancel
     * the run and (for a timeout) tell the user. Closing the cards happens under the chat's
     * mutex BEFORE the run leaves the store, so an approval racing the deadline either lands
     * first (and the run resumes) or finds the card already closed.
     */
    private suspend fun expireParked(conversationId: String): Result {
        val parkedRun = BotRoutineApprovals.get(conversationId) ?: return Result.success()
        if (parkedRun.resuming) return Result.success()
        val deleted = repo.getById(parkedRun.jobId) == null
        if (!deleted && !BotRoutineApprovalRules.isExpired(parkedRun, System.currentTimeMillis())) {
            // Re-parked since this timer was armed; a fresh timer covers the new wait.
            return Result.success()
        }
        val convId = runCatching { Uuid.parse(conversationId) }.getOrNull()
        if (convId != null) {
            val reason = applicationContext.getString(com.clma.app.R.string.bot_routine_approval_expired_reason)
            val closed = runCatching { chatService.closePendingTools(convId, reason) }
                .onFailure { Log.w(TAG, "expireParked: closing cards failed for $conversationId", it) }
                .getOrNull()
            if (!deleted && closed == 0) {
                // Every card was answered just before the deadline: that answer queued a
                // resume, which finishes the run.
                return Result.success()
            }
        }
        BotRoutineApprovals.remove(conversationId)
        if (deleted) {
            // The routine and its run history are gone; only the ledger row is left to close.
            parkedRun.ledgerId?.let { agentRunRepo.markTerminal(it, AgentRunStatus.cancelled, "routine_deleted") }
            return Result.success()
        }
        writeRunRow(parkedRun, OUTCOME_APPROVAL_EXPIRED, "no answer within ${BotRoutineApprovalRules.APPROVAL_TTL_MS / 3_600_000L}h")
        parkedRun.ledgerId?.let { agentRunRepo.markTerminal(it, AgentRunStatus.cancelled, OUTCOME_APPROVAL_EXPIRED) }
        BotRoutineRuns.notifyExpired(applicationContext, conversationId, botName(parkedRun.assistantId), parkedRun.jobName)
        return Result.success()
    }

    private suspend fun recordRun(
        jobId: String,
        scheduledAtMs: Long,
        outcome: String,
        mode: String,
        convId: Uuid?,
        errorMessage: String?,
    ) {
        // Used for the concurrent_skip early return only. doWork() is already a suspend
        // function on the worker's dispatcher, so this insert runs inline. No runBlocking
        // bridge needed (which would have blocked the worker thread).
        runCatching {
            runRepo.insert(ScheduledJobRunEntity(
                id = Uuid.random().toString(),
                jobId = jobId,
                mode = mode,
                scheduledAtMs = scheduledAtMs,
                startedAtMs = scheduledAtMs,
                finishedAtMs = scheduledAtMs,
                outcome = outcome,
                conversationId = convId?.toString(),
                errorMessage = errorMessage?.take(500),
            ))
        }.onFailure { Log.w(TAG, "recordRun failed", it) }
    }

    private fun postFailureNotification(
        jobId: String,
        jobName: String,
        errorMessage: String,
        conversationId: Uuid?,
    ) {
        val ctx = applicationContext
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "Scheduled jobs", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val detail = "$jobName: $errorMessage"
        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setContentTitle("Scheduled job failed")
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
        if (conversationId != null) {
            val intent = android.content.Intent(ctx, com.clma.app.RouteActivity::class.java).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                data = android.net.Uri.parse("clma://cron/$conversationId")
                putExtra("conversationId", conversationId.toString())
            }
            builder.setContentIntent(android.app.PendingIntent.getActivity(
                ctx, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE,
            ))
        }
        try {
            // jobId is the job's DB primary key (a stable UUID string), not the
            // user-editable display name — two jobs can share a name (and thus a
            // hashCode) but never an id, so this can't collide across different jobs
            // while still hashing to the same value across reruns of the same job.
            NotificationManagerCompat.from(ctx).notify(jobId.hashCode(), builder.build())
        } catch (_: SecurityException) { /* POST_NOTIFICATIONS not granted — fine */ }
    }

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
