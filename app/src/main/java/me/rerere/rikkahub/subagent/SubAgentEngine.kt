package me.rerere.rikkahub.subagent

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.agentdef.AgentDefinitionRepository
import me.rerere.rikkahub.data.agentdef.AgentDefinitionResolver
import me.rerere.rikkahub.data.agentrun.AgentRunKind
import me.rerere.rikkahub.data.agentrun.AgentRunRepository
import me.rerere.rikkahub.data.agentrun.AgentRunStatus
import me.rerere.rikkahub.data.ai.tools.HeadlessConversations
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.usage.OrchestrationBudget
import me.rerere.rikkahub.data.usage.OrchestrationGate
import me.rerere.rikkahub.data.usage.RunUsageSummary
import me.rerere.rikkahub.data.usage.RunUsageSummaryFactory
import me.rerere.rikkahub.data.usage.UsageLedger
import me.rerere.rikkahub.data.usage.UsageRunContexts
import me.rerere.rikkahub.data.ai.AssistantResolver
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.service.ChatService
import kotlin.uuid.Uuid

private const val TAG = "SubAgentEngine"

/**
 * How long [SubAgentEngine.notifyParentIfBackground] waits for the parent conversation to be idle
 * before it posts the completion notice anyway. Was 5 minutes, which made a background sub-agent
 * that finished while the user was mid-turn look like it had hung — the result is only useful once
 * the user sees it, and a busy parent is the common case. A minute is long enough for the usual
 * generation gap and short enough that the notice still feels attached to the work.
 */
private const val PARENT_NOTIFY_IDLE_WAIT_MS = 60_000L

/**
 * Turn a wait-for-completion outcome into a stop decision, stopping the still-running
 * generation via [stop] when the wait timed out. Returns true on timeout, false on
 * natural completion. The generation itself is NOT cancelled by withTimeoutOrNull — that
 * only abandons the wait, leaving the LLM call running in ChatService's own session job.
 * Left uncalled, a timed-out sub-agent keeps burning tokens (and, if it later succeeds,
 * races a duplicate parallel run against whatever the parent does next). [stop] is
 * responsible for its own failure handling (see the runCatching wrapper around
 * chatService.stopGeneration at the call site in [SubAgentEngine.executeRun]) — kept out
 * of this pure function so it stays testable without touching android.util.Log, which
 * isn't mocked in this module's plain-JVM unit tests. Split out the same way
 * CronJobWorker.finishRunLlm is, so a JVM test can pin the stop-on-timeout contract
 * without a live ChatService.
 */
internal suspend fun finishSubAgentWait(completed: Boolean, stop: suspend () -> Unit): Boolean {
    if (!completed) {
        stop()
        return true
    }
    return false
}


/**
 * Phase 11 — sub-agent dispatch engine.
 *
 * The engine reuses the existing cron-headless dispatch pattern (mark conv headless,
 * sendMessage, await generation flow's terminal state). It deliberately does NOT
 * re-implement [me.rerere.rikkahub.data.ai.GenerationHandler] — that path is already
 * battle-tested and any duplicate would diverge.
 *
 * Recursion guard: SubAgentEngine refuses to dispatch if the calling conversation is
 * itself headless (i.e. we're already inside a sub-agent / cron / external-automation
 * run). The `subagent_*` tools stay REGISTERED inside a headless conversation — the older
 * claim that [me.rerere.rikkahub.data.ai.tools.LocalTools]' standard gating removes them
 * was wrong: registration keys off `LocalToolOption.SubAgents`, and a sub-agent run reuses
 * its parent's assistant, so that option is still in the list. The load-bearing guard is
 * the trio of `isHeadless` checks (the dispatch tool's entry guard, this engine's dispatch
 * entry, and the parent-notification path). Since T-09 / (8) the frozen tool surface
 * ([SubAgentSurface]) additionally removes the `subagent_*` handles from a sub-agent
 * conversation altogether, so the model never sees them and never spends a trip on a
 * refusal — but that is a cost optimisation on top of the guards, not a replacement for
 * them. v1: no recursion.
 *
 * Concurrency caps:
 *  - Per-assistant cap from [me.rerere.rikkahub.data.model.Assistant.maxConcurrentSubAgents]
 *  - Global cap from `Settings.subAgentGlobalConcurrencyCap` (default
 *    [SubAgentDefaults.GLOBAL_CONCURRENCY_CAP])
 *  - Both enforced at dispatch entry as a BOUNDED WAIT: an over-cap dispatch queues for a free
 *    slot for up to [SubAgentDefaults.SLOT_WAIT_TIMEOUT_MS] and only fails if the wait expires.
 *    Before this, the same check failed fast, which threw away most runs of a parallel burst —
 *    exactly the workload the feature exists for.
 */
class SubAgentEngine(
    private val registry: SubAgentRegistry,
    private val conversationRepo: ConversationRepository,
    private val settingsStore: SettingsStore,
    private val appScope: AppScope,
    /**
     * Phase 24 — unified AgentRun ledger writer. The [SubAgentRegistry] is in-memory only,
     * so a backgrounded sub-agent does NOT survive process death — its registry entry is
     * gone on restart. Writing each sub-agent run to the persistent ledger closes that gap:
     * a run left `running` when the process dies is flipped to `process_lost` by
     * [me.rerere.rikkahub.data.agentrun.AgentRunBootRecovery] on next start, so the user
     * (and `subagent_get`, via the ledger) can see what actually happened. No DI-cycle
     * risk: AgentRunRepository depends only on its DAO.
     */
    private val agentRunRepo: AgentRunRepository,
    /**
     * P2-06b — the expert library, the successor to `Settings.subAgents`. [executeRun] resolves
     * the requested `agent` name against it, and the derived assistant that gives the run its
     * own tool surface is synthesised from the same definition. Injected rather than read off
     * `Settings` because the library is a database, not a preference blob.
     */
    private val agentDefinitionRepository: AgentDefinitionRepository,
    private val folderRepository: FolderRepository,
    /**
     * P2-13 — the accounting ledger, read here (never written): the budget gate sums what
     * this conversation's sub-agents already cost before another dispatch is allowed, and a
     * finished run reads its own rows back so the parent gets real numbers, not zeroes.
     */
    private val usageLedger: UsageLedger,
) {

    /**
     * [ChatService] is resolved lazily via Koin to break the construction cycle:
     *   - [ChatService] constructor takes [LocalTools]
     *   - [LocalTools] constructor takes [SubAgentEngine] (so subagent_dispatch can fire)
     *   - [SubAgentEngine] needs [ChatService] only at dispatch time (sendMessage), so
     *     eager constructor injection here would close the cycle.
     * Same lazy-Koin pattern as [me.rerere.rikkahub.workflow.execution.WorkflowEngine.localTools].
     * Verified post-DI-fix 2026-05-08 — installed APK reaches MainActivity without crash.
     */
    private val chatService: ChatService by lazy {
        org.koin.java.KoinJavaComponent.getKoin().get<ChatService>()
    }

    /**
     * Phase 24 — maps a sub-agent run id to its `agent_runs` ledger row id. Populated when
     * the run is dispatched, consulted by [executeRun] / [markTerminal] when transitioning
     * the ledger row, removed when the run reaches a terminal status. A run with no entry
     * here simply skips the ledger write (best-effort — the ledger never breaks a run).
     */
    private val ledgerIds = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Serialises the "is there a free concurrency slot?" check and the [SubAgentRegistry.addPending]
     * that claims it, so two dispatches waking at the same instant cannot both see the last slot as
     * free. Held only for those few non-suspending map operations — the actual waiting happens
     * outside it, so a queued dispatch never blocks the reserve attempts of the others.
     */
    private val slotMutex = Mutex()

    /**
     * P2-15 — what the budget gate learned about this dispatch's orchestration, whether or not it
     * refused. [refusal] is the envelope to raise (null = allowed); [remaining] is how many
     * orchestration tokens were still unspent when this dispatch was admitted (null = no ceiling,
     * so nothing to report). Carrying both out of the one read is what lets the accepted envelope
     * tell a model its headroom BEFORE it spends it, instead of only after it hits the wall.
     *
     * The count is the ceiling minus what the orchestration had already spent — the run being
     * admitted is not in it yet, because nothing can know its cost until it has run. That is the
     * same pre-flight contract the gate itself is built on (D8), so the number the model reads and
     * the number the next dispatch is judged against are the same number.
     */
    private data class BudgetGate(val refusal: DispatchResult.Reject?, val remaining: Long?)

    sealed class DispatchResult {
        data class Ok(val run: SubAgentRun, val budgetRemaining: Long? = null) : DispatchResult()
        data class Reject(val error: String, val detail: String) : DispatchResult()
    }

    /**
     * Dispatch a sub-agent. For foreground runs, blocks until terminal status; for
     * background, returns immediately with a PENDING-then-RUNNING run that the caller
     * can poll via subagent_get.
     */
    suspend fun dispatch(
        parentAssistantId: String,
        parentChatId: String?,
        request: SubAgentRequest,
    ): DispatchResult = withContext(Dispatchers.Default) {
        // Recursion guard: if the caller is in a headless context already (cron job /
        // workflow / another sub-agent), reject. v1 does not allow nested sub-agents.
        if (parentChatId != null) {
            val parentUuid = runCatching { Uuid.parse(parentChatId) }.getOrNull()
            if (parentUuid != null && HeadlessConversations.isHeadless(parentUuid)) {
                return@withContext DispatchResult.Reject(
                    "no_recursion",
                    "sub-agent dispatch is not allowed from inside another headless run"
                )
            }
        }
        val validation = SubAgentRequestValidator.validate(request)
        if (validation is SubAgentRequestValidator.Result.Reject) {
            return@withContext DispatchResult.Reject(validation.error, validation.detail)
        }
        val profile = currentProfile()
        if (!SubAgentDispatchProfileRules.isDispatchEnabled(profile)) {
            return@withContext DispatchResult.Reject(
                "disabled_by_policy",
                "sub-agent dispatch is disabled (profile = $profile). Do all work inline.",
            )
        }
        val cleaned = (validation as SubAgentRequestValidator.Result.Ok).request.let {
            it.copy(
                maxTrips = SubAgentDispatchProfileRules.clampMaxTrips(profile, it.maxTrips),
                timeoutSeconds = SubAgentDispatchProfileRules.clampTimeout(profile, it.timeoutSeconds),
            )
        }

        // P2-13 — the budget gate. Judged before a concurrency slot is taken, so an
        // over-budget orchestration is refused on policy rather than on capacity.
        val gate = checkOrchestrationBudget(parentAssistantId, parentChatId, cleaned)
        gate.refusal?.let { refusal ->
            return@withContext refusal
        }

        // Concurrency cap, enforced as a BOUNDED WAIT rather than a fail-fast. A parallel burst
        // (the whole point of the feature) used to lose every run past the cap; now each dispatch
        // queues for a free slot and only gives up when the wait expires. Global is read from
        // settings (falling back to the default) and checked first as the coarser limit.
        val globalCap = globalConcurrencyCap()
        val perAssistantCap = currentAssistantCap(parentAssistantId)

        val runId = Uuid.random().toString()
        val now = System.currentTimeMillis()
        val initialRun = SubAgentRun(
            id = runId,
            parentChatId = parentChatId,
            parentAssistantId = parentAssistantId,
            label = cleaned.label?.takeIf { it.isNotBlank() } ?: cleaned.task.take(60),
            task = cleaned.task,
            modelId = cleaned.modelId,
            tools = cleaned.tools,
            runInBackground = cleaned.runInBackground,
            noResult = cleaned.noResult,
            timeoutSeconds = cleaned.timeoutSeconds,
            maxTrips = cleaned.maxTrips,
            status = SubAgentStatus.PENDING,
            startedAtMs = now,
        )

        // PENDING holds a slot too (see SubAgentRegistry.activeCount*), so claiming the slot IS the
        // addPending — done atomically with the capacity check under [slotMutex] so two dispatches
        // waking together cannot both take the last slot. Waiting happens outside the lock.
        val waitDeadlineMs = now + SubAgentDispatchProfileRules.caps(profile).slotWaitMs
        var slotRefusal: String? = null
        while (true) {
            val reserved = slotMutex.withLock {
                val hasGlobalSlot = registry.globalActiveCount() < globalCap
                val hasAssistantSlot =
                    registry.activeCountForAssistant(parentAssistantId) < perAssistantCap
                if (hasGlobalSlot && hasAssistantSlot) {
                    registry.addPending(initialRun)
                    true
                } else {
                    false
                }
            }
            if (reserved) break
            val remainingMs = waitDeadlineMs - System.currentTimeMillis()
            if (remainingMs <= 0L) {
                slotRefusal = "waited ${SubAgentDefaults.SLOT_WAIT_TIMEOUT_MS / 1000}s for a free " +
                    "sub-agent slot; the global cap ($globalCap) and this assistant's " +
                    "max_concurrent_sub_agents cap ($perAssistantCap) are still reached — " +
                    "retry once a running sub-agent finishes"
                break
            }
            delay(minOf(SubAgentDefaults.SLOT_RETRY_INTERVAL_MS, remainingMs))
        }
        if (slotRefusal != null) {
            return@withContext DispatchResult.Reject("concurrency_cap_reached", slotRefusal)
        }

        // Phase 24 — open the cross-pillar ledger row. domain_id is the sub-agent run id.
        // The row starts in `queued` (the execution coroutine hasn't been launched yet);
        // executeRun() flips it to `running`. If the process dies before then, boot
        // recovery flips the stranded `queued` row to `process_lost`.
        val ledgerId = agentRunRepo.open(
            kind = AgentRunKind.SubAgent,
            domainId = runId,
            parentRunId = parentChatId,
            status = AgentRunStatus.queued,
            metadata = buildJsonObject {
                put("label", initialRun.label)
                put("parent_assistant_id", parentAssistantId)
                put("run_in_background", cleaned.runInBackground)
            },
        )
        ledgerIds[runId] = ledgerId

        val executionJob = appScope.launch(Dispatchers.IO) {
            executeRun(runId, parentAssistantId, parentChatId, cleaned)
        }
        registry.setJob(runId, executionJob)

        // P2-14a — the cancellation safety net. [runSubAgentBody] writes a terminal status only from
        // inside its own try/catch, but a run can be cancelled at any suspension point BEFORE that
        // try — the ledger's `running` write, the settings read, the expert and model resolutions —
        // and a job cancelled before its coroutine ever starts never enters the body at all. With no
        // `invokeOnCompletion` anywhere on this path (Phase 11 through P2-13), such a run stranded in
        // PENDING / RUNNING for the life of the process: it held a concurrency slot
        // (`globalActiveCount` counts both) and `subagent_get` reported it as running forever. This
        // handler is the one place that sees every exit, including the ones the body never ran.
        executionJob.invokeOnCompletion { cause ->
            val current = registry.get(runId)?.status
            if (current != SubAgentStatus.PENDING && current != SubAgentStatus.RUNNING) {
                return@invokeOnCompletion
            }
            val cancelled = cause == null || cause is kotlinx.coroutines.CancellationException
            val status = if (cancelled) SubAgentStatus.CANCELLED else SubAgentStatus.FAILED
            val reason = when {
                cause is kotlinx.coroutines.CancellationException ->
                    "cancelled before the run reached a terminal status"
                cause != null -> "${cause::class.simpleName}: ${cause.message.orEmpty()}"
                else -> "completed without reaching a terminal status"
            }
            // Registry first, synchronously, so the foreground dispatch that joined this job (or a
            // `subagent_get` racing the cancel) observes the terminal state at once. The ledger
            // write is suspend, so it hops onto the app scope — best effort, like every other
            // ledger write on this path, and out of [markTerminal] so the registry flip above can
            // never be delayed by a database call.
            markTerminalInRegistry(runId, status, reason)
            ledgerIds.remove(runId)?.let { ledgerId ->
                appScope.launch(Dispatchers.IO) {
                    runCatching { agentRunRepo.markTerminal(ledgerId, status.toLedgerStatus(), reason) }
                }
            }
            registry.clearJob(runId)
        }


        if (cleaned.runInBackground) {
            // Return immediately; final status delivered via registry observation.
            DispatchResult.Ok(registry.get(runId) ?: initialRun, gate.remaining)
        } else {
            // Foreground — block until terminal.
            try {
                executionJob.join()
            } catch (t: Throwable) {
                Log.w(TAG, "foreground sub-agent join failed for $runId", t)
            }
            DispatchResult.Ok(registry.get(runId) ?: initialRun, gate.remaining)
        }
    }

    private suspend fun currentAssistantCap(parentAssistantId: String): Int {
        val profile = currentProfile()
        if (!SubAgentDispatchProfileRules.isDispatchEnabled(profile)) return 0
        val profileCap = SubAgentDispatchProfileRules.caps(profile).perAssistantCap
        val asstUuid = runCatching { Uuid.parse(parentAssistantId) }.getOrNull() ?: return profileCap
        val settings = settingsStore.settingsFlow.first()
        val asst = AssistantResolver.byId(settings, asstUuid) ?: return profileCap
        return SubAgentDispatchProfileRules.effectivePerAssistantCap(
            profile,
            asst.maxConcurrentSubAgents.coerceIn(
                SubAgentDefaults.MIN_PER_ASSISTANT_CAP,
                SubAgentDefaults.MAX_PER_ASSISTANT_CAP,
            ),
        )
    }

    /**
     * The global (all-assistants) concurrency ceiling, read live from
     * `Settings.subAgentGlobalConcurrencyCap`. Best-effort like every other settings read on the
     * dispatch path: a settings hiccup degrades to the compiled default rather than blocking a
     * dispatch, and the value is clamped so a hand-edited preference cannot park it outside the
     * supported range.
     */
    private suspend fun currentProfile(): SubAgentDispatchProfile =
        runCatching { settingsStore.settingsFlow.first().subAgentDispatchProfile }
            .getOrDefault(SubAgentDispatchProfile.NEVER)

    private suspend fun globalConcurrencyCap(): Int =
        SubAgentDispatchProfileRules.caps(currentProfile()).globalCap

    /**
     * P2-13 — refuse the dispatch when this conversation has already spent its orchestration
     * budget. Returns null to allow.
     *
     * The ceiling is the expert's own `tokenBudget` when the dispatch names one, otherwise the
     * parent assistant's `orchestrationTokenBudget` (D8) — [OrchestrationBudget.effectiveBudget]
     * is the single place that precedence lives. Nothing is enforced when no ceiling is
     * configured anywhere, which is what keeps an untouched install byte-for-byte as it was;
     * the ledger is not even read in that case.
     *
     * A conversation-less dispatch (cron / workflow / external automation) has no orchestration
     * root to sum against, so it is never gated — but when a ceiling IS configured it is logged
     * rather than bypassed in silence (P2-14c). Every read here is best-effort: a ledger or
     * settings hiccup degrades to "allow", because telemetry is not allowed to break a dispatch
     * — the refusal exists to protect a budget, not to add a new failure mode.
     */
    private suspend fun checkOrchestrationBudget(
        parentAssistantId: String,
        parentChatId: String?,
        request: SubAgentRequest,
    ): BudgetGate {
        val assistantBudget = runCatching {
            val asstUuid = Uuid.parse(parentAssistantId)
            settingsStore.settingsFlow.first().let { AssistantResolver.byId(it, asstUuid) }
        }.getOrNull()?.orchestrationTokenBudget
        // Cheap short-circuit: with no assistant ceiling and no named expert there is nothing
        // to resolve, so the default (budget-free) path pays neither the settings nor the
        // expert read.
        if (assistantBudget == null && request.agentName == null) return BudgetGate(null, null)
        val expertBudget = runCatching {
            (agentDefinitionRepository.resolveByName(request.agentName)
                as? AgentDefinitionResolver.Result.Resolved)?.definition?.tokenBudget
        }.getOrNull()
        val budget = OrchestrationBudget.effectiveBudget(assistantBudget, expertBudget)
            ?: return BudgetGate(null, null)
        // P2-14c — a ceiling IS configured, but a dispatch with no parent chat id (cron / workflow /
        // external automation constructing a SubAgentRequest directly) has no orchestration root to
        // sum against, so there is nothing to enforce it with. This used to be a silent bypass; log
        // it so a future caller that hands the engine a request directly cannot escape its budget
        // without a trace. The outcome stays "allow" on purpose — refusing would turn a missing
        // anchor into a brand-new failure mode for a path that never had a budget to begin with.
        if (parentChatId == null) {
            Log.w(
                TAG,
                "orchestration budget $budget configured for assistant $parentAssistantId, but the " +
                    "dispatch has no parent chat id — no orchestration root to enforce against; allowing",
            )
            return BudgetGate(null, null)
        }
        val used = runCatching { usageLedger.tokensForOrchestration(parentChatId) }.getOrDefault(0L)
        val decision = OrchestrationGate.decide(used, budget)
        // P2-15 — the same read that judges the dispatch also reports the headroom, so the accepted
        // path can tell the model what is left before it spends it. The refusal carries it too (it
        // is 0 there by construction), so both exits describe the orchestration the same way.
        val remaining = OrchestrationBudget.remaining(used, budget)
        if (decision !is OrchestrationGate.Decision.Refuse) return BudgetGate(null, remaining)
        return BudgetGate(
            DispatchResult.Reject(
                error = OrchestrationGate.ERROR_CODE,
                detail = OrchestrationGate.refusalDetail(decision),
            ),
            remaining,
        )
    }

    /**
     * P2-13 — freeze what this run cost onto its registry entry, so the dispatcher's tool result
     * (foreground) and `subagent_get` (either) carry real numbers instead of the zeroes Phase 11
     * declared and nobody ever filled.
     *
     * Must run BEFORE the terminal status is written: a run is frozen once it is terminal, and
     * the wake-up message reads the same entry to tell the parent what the dispatch cost. Sums
     * by conversation and filters on the run id, so the auxiliary rows the run did not pay for
     * (title generation carries no run id) stay out. Best-effort: a read failure leaves the
     * zeroes and never fails the run.
     */
    private suspend fun attachRunUsage(runId: String, conversationId: String) {
        val summary = runCatching {
            RunUsageSummaryFactory.from(
                records = usageLedger.recordsForConversation(conversationId),
                runId = runId,
            )
        }.getOrNull() ?: return
        registry.update(runId) {
            it.copy(
                tokensIn = summary.inputTokens,
                tokensOut = summary.outputTokens,
                usageCalls = summary.calls,
            )
        }
    }

    private suspend fun executeRun(
        runId: String,
        parentAssistantId: String,
        parentChatId: String?,
        request: SubAgentRequest,
    ) {
        // P2-08 — hold the chat foreground service for the run's ENTIRE life, not just the model
        // calls inside it. ChatService already claims the service around each generation, but a
        // sub-agent lives through gaps where no generation is in flight: tool execution, a
        // concurrency-slot wait, and the parent-notification wait that can last five minutes after
        // the run itself produced its result. A background dispatch can outlive the parent turn
        // that started it, so those gaps used to run with no foreground claim at all — exactly the
        // window the platform freezes or reclaims.
        //
        // Taken here and released in the `finally` below, so the hold can never outlive the run:
        // with no active run the tracker drops to zero and the service is withdrawn (the P2-08 red
        // line). The claim is one unit on the SAME tracker the generations use, so an overlapping
        // generation never has its service torn down by an orchestration ending, and vice versa.
        val releaseForegroundHold = chatService.retainForegroundForActiveRun()
        try {
            runSubAgentBody(runId, parentAssistantId, parentChatId, request)
        } finally {
            releaseForegroundHold()
            // P2-12d — the run is over; drop its attribution so no later call inherits it.
            UsageRunContexts.unmarkRun(runId)
        }
    }

    /**
     * The body of a sub-agent run, split out of [executeRun] so the P2-08 foreground hold wraps
     * every return path — the run has early returns for a bad parent id, an unresolvable expert,
     * and an unresolvable model — without touching the run logic itself.
     */
    private suspend fun runSubAgentBody(
        runId: String,
        parentAssistantId: String,
        parentChatId: String?,
        request: SubAgentRequest,
    ) {
        registry.update(runId) { it.copy(status = SubAgentStatus.RUNNING) }
        ledgerIds[runId]?.let { agentRunRepo.setStatus(it, AgentRunStatus.running) }

        val parentAsstUuid = runCatching { Uuid.parse(parentAssistantId) }.getOrNull()
            ?: run {
                markTerminal(runId, SubAgentStatus.FAILED, "bad parent assistant id")
                return
            }
        val settings = settingsStore.settingsFlow.first()
        // T-09 / (8) — does this run's parent assistant want the sub-agent tool surface frozen?
        // Read here even though subagent_dispatch also reads the field from its
        // ToolInvocationContext: both read the SAME persisted Assistant field, so they cannot
        // disagree — and the tool needs it at tool-CONSTRUCTION time to gate whether the `tools`
        // parameter is even described in the schema.
        val parentAssistant = AssistantResolver.byId(settings, parentAsstUuid)
        val freezeToolSurface = parentAssistant?.enableSubAgentToolSurface == true
        // #36, re-homed onto the expert library by P2-06b: resolve `agent` before `model_id`
        // so model_id's own resolution can fall back to the expert's model when model_id is
        // absent - `model_id` still wins when both are given (see SubAgentTools' parameter
        // description). `resolveByName` refreshes from the store first, so a dispatch never
        // resolves a name against a stale roster.
        val definitionResolution = agentDefinitionRepository.resolveByName(request.agentName)
        val definition = when (definitionResolution) {
            is AgentDefinitionResolver.Result.NotRequested -> null
            is AgentDefinitionResolver.Result.Resolved -> definitionResolution.definition
            is AgentDefinitionResolver.Result.Failed -> {
                markTerminal(runId, SubAgentStatus.FAILED, definitionResolution.message)
                return
            }
        }
        // P2-04 — the child's OWN tool surface, when its expert defines one (local tools /
        // per-tool opt-out / MCP servers / skills / the D9 namespace). Null = no surface of its
        // own, so the run keeps inheriting the parent's assistant verbatim (the pre-P2-04
        // behaviour).
        val childAssistant = SubAgentSurface.resolveChildAssistant(parentAssistant, definition)
        val modelResolution = resolveSubAgentModel(
            SubAgentModelResolver.resolve(request.modelId, settings.providers),
            definition,
        )
        val resolvedChatModelId = when (modelResolution) {
            is SubAgentModelResolver.Result.Inherit -> null
            is SubAgentModelResolver.Result.Resolved -> modelResolution.modelId
            is SubAgentModelResolver.Result.Failed -> {
                markTerminal(runId, SubAgentStatus.FAILED, modelResolution.message)
                return
            }
        }
        // The expert's system prompt is prepended to the task text itself (same technique as
        // the wrap-up instruction below) rather than plumbed into Conversation/ChatService -
        // sub-agent runs don't get a per-run system prompt override today, and wiring one in
        // is out of scope here (see Non-goals: don't change how runs execute).
        val effectiveTask = definition?.systemPrompt?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { "$it\n\n${request.task}" }
            ?: request.task
        // T-04 / (4) - the caller asked for part of its own conversation to travel with the
        // task. A sub-agent's conversation starts empty, so the ONLY channel is this text:
        // materialise the referenced turns into the first user message rather than trying to
        // share history. Best-effort by design - a lookup failure degrades to the pre-T-04
        // behaviour (task alone) instead of failing the run.
        val taskWithContext = withParentContext(parentChatId, request, effectiveTask)
        // D3 — when the caller asked for it, carry the parent's newest user-message audio/video
        // attachments into the sub-agent's first message. Best-effort like the context refs
        // above: any lookup failure degrades to "no media" rather than failing the run.
        val mediaParts = if (request.attachParentMedia) withParentMedia(parentChatId) else emptyList()
        if (mediaParts.isNotEmpty()) {
            registry.update(runId) { it.copy(mediaParts = mediaParts.size) }
        }
        // P2-23 (D7) - file this conversation into the assistant's sub-agent archive folder, if
        // one is configured AND still exists. The list shows only unfiled conversations, so
        // filing is what keeps this out of the main list. A stale setting (folder since deleted)
        // degrades to "unfiled" rather than leaving a dangling folder id on the conversation.
        val archiveFolder = SubAgentArchiveRules
            .targetFolderId(settingsStore.settingsFlow.value.subAgentArchiveFolders, parentAsstUuid.toString())
            ?.let { raw -> runCatching { Uuid.parse(raw) }.getOrNull() }
            ?.takeIf { id -> runCatching { folderRepository.getFolderById(id) }.getOrNull() != null }
        val conv = Conversation.ofId(
            id = Uuid.random(),
            assistantId = parentAsstUuid,
            newConversation = true,
        ).copy(
            title = "[Sub-agent] ${request.label?.take(40) ?: request.task.take(40)}",
            chatModelId = resolvedChatModelId,
            folderId = archiveFolder,
        )
        conversationRepo.insertConversation(conv)
        chatService.initializeConversation(conv.id)
        HeadlessConversations.mark(conv.id)
        // P2-12d — attribute this run's model calls to it, so the ledger can price the
        // dispatch and the parent→child tree has a run id to hang off. The parent id is the
        // conversation that dispatched it (the same value written to agent_runs.parent_run_id).
        UsageRunContexts.mark(
            conversationId = conv.id.toString(),
            runId = runId,
            parentRunId = parentChatId,
        )
        // T-09 / (8) + P2-04 — freeze this run's tool surface for as long as it lives; ChatService
        // applies the filter at every tool-assembly site. A record is written when EITHER knob is
        // on: the parent assistant's `enableSubAgentToolSurface` (which also narrows the surface to
        // the dispatcher's `tools` allow-list), or the child's own surface (P2-04). With neither,
        // no record is written at all, so `apply` stays an identity and the child's schemas are
        // byte-for-byte what they were.
        if (freezeToolSurface || childAssistant != null) {
            // Belt and braces: subagent_dispatch already REJECTS a blocked name outright, but the
            // engine is the layer that actually owns the surface, and a future caller (a workflow
            // step, an external automation, a test) could hand us a SubAgentRequest directly.
            // Running the requested names through the policy here means a blocked name can never
            // reach a frozen surface no matter who asked for it. An empty result means "no
            // narrowing", which is exactly what `request.tools == null` means.
            //
            // P2-04 — `assistant` is the child's own surface when its profile defined one. The
            // `tools` allow-list stays gated by the parent's `enableSubAgentToolSurface`: its
            // schema is only described when that flag is on, so a profile-supplied surface alone
            // never honours a parameter the model was never told about.
            SubAgentSurface.freeze(
                conversationId = conv.id,
                assistant = childAssistant,
                requested = if (freezeToolSurface) {
                    SubAgentToolSurface.safeNames(request.tools.orEmpty())
                } else {
                    null
                },
            )
        }
        try {
            // Prepend a wrap-up instruction. Some models naturally write a summary paragraph
            // after their tool-call sequence; others stop after the last tool result and emit
            // no closing text. Without explicit text the parent has nothing to harvest and
            // the sub-agent's findings are lost.
            val taskWithWrapup = buildString {
                append(taskWithContext)
                appendLine()
                appendLine()
                append("When you have finished, end with one short paragraph in plain text that summarises what you did and what you found. Do NOT stop on a tool call — finish with assistant text. The dispatcher harvests only your final text reply, so this paragraph is the entire response the parent sees.")
            }
            // D3 — media first, then the task text, matching the order DashScope's own
            // multimodal examples use.
            chatService.sendMessage(
                conv.id,
                mediaParts + listOf(UIMessagePart.Text(taskWithWrapup)),
            )
            // The naive form `withTimeoutOrNull { …first { it == null } }` followed by a
            // `finished == null` check is BROKEN: `.first { it == null }` returns the matched
            // value — which IS null on successful completion (the Job? went to null when the
            // LLM finished). So `finished == null` was true on BOTH timeout AND success, and
            // every sub-agent looked TIMED_OUT despite actually finishing. Use a Unit sentinel
            // so the two outcomes are distinguishable.
            val completed: Unit? = withTimeoutOrNull(request.timeoutSeconds * 1000L) {
                chatService.getGenerationJobStateFlow(conv.id).first { it == null }
                Unit
            }
            val timedOut = finishSubAgentWait(completed = completed != null) {
                runCatching { chatService.stopGeneration(conv.id) }
                    .onFailure { Log.w(TAG, "sub-agent timeout: stopGeneration failed for $runId", it) }
            }
            if (timedOut) {
                attachRunUsage(runId, conv.id.toString())
                markTerminal(runId, SubAgentStatus.TIMED_OUT, "exceeded ${request.timeoutSeconds}-second cap")
                notifyParentIfBackground(parentChatId, registry.get(runId))
                return
            }
            // Harvest the assistant's final text from the conversation. Best-effort —
            // we read the latest persisted state of the conversation and concatenate any
            // text parts from the last assistant message. This mirrors how the
            // CronJobWorker treats LLM-mode jobs.
            val finalText = harvestFinalText(conv.id)
            attachRunUsage(runId, conv.id.toString())
            registry.update(runId) {
                it.copy(
                    status = SubAgentStatus.SUCCEEDED,
                    result = finalText,
                    finishedAtMs = System.currentTimeMillis(),
                )
            }
            ledgerIds.remove(runId)?.let {
                agentRunRepo.markTerminal(it, AgentRunStatus.succeeded)
            }
            notifyParentIfBackground(parentChatId, registry.get(runId))
        } catch (t: Throwable) {
            Log.w(TAG, "sub-agent run failed", t)
            attachRunUsage(runId, conv.id.toString())
            // CancellationException → CANCELLED, anything else → FAILED.
            val terminal = if (t is kotlinx.coroutines.CancellationException) SubAgentStatus.CANCELLED else SubAgentStatus.FAILED
            markTerminal(runId, terminal, "${t::class.simpleName}: ${t.message.orEmpty()}")
            notifyParentIfBackground(parentChatId, registry.get(runId))
        } finally {
            HeadlessConversations.unmark(conv.id)
            // T-09 / (8) + P2-04 — release in the same finally that unmarks the conversation, so a
            // cancelled / failed / timed-out run cannot leave a stale freeze behind. Releasing is
            // unconditional and safe: removing a record that was never written is a no-op.
            SubAgentSurface.release(conv.id)
            registry.clearJob(runId)
        }
    }

    private suspend fun markTerminal(runId: String, status: SubAgentStatus, error: String?) {
        markTerminalInRegistry(runId, status, error)
        // Phase 24 — mirror the terminal status into the cross-pillar ledger. TIMED_OUT and
        // FAILED both map to `failed`; CANCELLED maps to `cancelled`. (SUCCEEDED never
        // routes through here — it transitions the ledger row inline in executeRun.)
        ledgerIds.remove(runId)?.let { ledgerId ->
            agentRunRepo.markTerminal(ledgerId, status.toLedgerStatus(), error)
        }
    }

    /**
     * P2-14a — the registry half of [markTerminal], split out because the cancellation safety net
     * cannot suspend: it runs inside [kotlinx.coroutines.Job.invokeOnCompletion], which forces a
     * plain `(Throwable?) -> Unit` handler. `SubAgentRegistry.update` is a plain `StateFlow.update`,
     * so flipping the status needs no suspension and a caller polling `subagent_get` sees the
     * terminal state immediately.
     */
    private fun markTerminalInRegistry(runId: String, status: SubAgentStatus, error: String?) {
        registry.update(runId) {
            it.copy(
                status = status,
                error = error,
                finishedAtMs = System.currentTimeMillis(),
            )
        }
    }

    /** Sub-agent terminal status -> its `agent_runs` mirror: TIMED_OUT / FAILED both map to `failed`. */
    private fun SubAgentStatus.toLedgerStatus(): AgentRunStatus = when (this) {
        SubAgentStatus.CANCELLED -> AgentRunStatus.cancelled
        SubAgentStatus.SUCCEEDED -> AgentRunStatus.succeeded
        else -> AgentRunStatus.failed
    }

    /**
     * Wake the parent conversation when a backgrounded sub-agent finishes — the parent's
     * LLM gets a synthetic user message describing the completion and naturally synthesises
     * a reply. Without this, the parent has no way to know the sub-agent finished except by
     * the user manually asking "what happened?".
     *
     * Skip rules:
     *  - Foreground runs: dispatch is synchronous (executionJob.join() in dispatch()), so the
     *    tool result already carries the final state. No wake needed.
     *  - Parents in headless mode: would loop / fork weirdly with cron + sub-agent + workflow
     *    runs. The parent must be a regular interactive (in-app or Telegram-bot) conversation.
     *  - parentChatId / runs missing: defensive.
     *
     * Cancellation hygiene: ChatService.sendMessage cancels any in-flight generation in the
     * target conversation. To avoid stomping on a turn the user is engaged with, we wait
     * up to [PARENT_NOTIFY_IDLE_WAIT_MS] for the parent to be idle before posting. After that we post
     * anyway — better to interrupt than to silently lose the completion.
     */
    private suspend fun notifyParentIfBackground(parentChatId: String?, run: SubAgentRun?) {
        if (parentChatId == null || run == null || !run.runInBackground) return
        val parentUuid = runCatching { Uuid.parse(parentChatId) }.getOrNull() ?: return
        if (HeadlessConversations.isHeadless(parentUuid)) return

        val message = buildString {
            appendLine("[Sub-agent ${run.label} — ${run.status.name}]")
            run.error?.takeIf { it.isNotBlank() }?.let {
                appendLine("Error: $it")
            }
            RunUsageSummaryFactory.line(
                RunUsageSummary(
                    calls = run.usageCalls,
                    inputTokens = run.tokensIn,
                    outputTokens = run.tokensOut,
                )
            )?.let { appendLine(it) }
            if (!run.noResult) {
                run.result?.takeIf { it.isNotBlank() }?.let {
                    appendLine()
                    append(it)
                }
            }
        }.trimEnd()

        runCatching {
            withTimeoutOrNull(PARENT_NOTIFY_IDLE_WAIT_MS) {
                chatService.getGenerationJobStateFlow(parentUuid).first { it == null }
                Unit
            }
            chatService.sendMessage(parentUuid, listOf(UIMessagePart.Text(message)))
        }.onFailure {
            Log.w(TAG, "failed to notify parent $parentChatId of subagent completion", it)
        }
    }

    /**
     * T-04 / (4) - turn `context_refs` into text on the task, or return the task untouched.
     *
     * Returns the task unchanged (pre-T-04 behaviour, byte for byte) when: the feature was
     * not requested, there is no caller conversation (cron / workflow / external automation
     * dispatch from a context that has no user-facing history), the conversation is gone,
     * or every candidate turn is blank after the digest's filtering. A sub-agent run must
     * never fail because a context reference could not be resolved - the task is what
     * matters, and the caller can still see it ran.
     */
    private suspend fun withParentContext(
        parentChatId: String?,
        request: SubAgentRequest,
        task: String,
    ): String {
        val turns = request.contextRefs?.recentTurns ?: 0
        if (turns <= 0 || parentChatId == null) return task
        val parentUuid = runCatching { Uuid.parse(parentChatId) }.getOrNull() ?: return task
        val digest = runCatching {
            val conv = conversationRepo.getConversationById(parentUuid) ?: return@runCatching null
            // `selectIndex` picks the live branch of each node; the unselected siblings of an
            // edited/regenerated turn are deliberately not carried.
            val selected = conv.messageNodes.mapNotNull { node -> node.messages.getOrNull(node.selectIndex) }
            SubAgentContextDigest.render(SubAgentContextDigest.turnsFrom(selected, turns))
        }.getOrNull()
        return if (digest == null) task else digest + "\n\n" + task
    }

    /**
     * D3 — the parent conversation's newest user-message audio/video parts, or none.
     *
     * Mirrors [withParentContext]'s defensive posture: a missing conversation, an unparsable
     * id or a read failure all degrade to an empty list, so media never fails a dispatch.
     */
    private suspend fun withParentMedia(parentChatId: String?): List<UIMessagePart> {
        if (parentChatId == null) return emptyList()
        val parentUuid = runCatching { Uuid.parse(parentChatId) }.getOrNull() ?: return emptyList()
        return runCatching {
            val conv = conversationRepo.getConversationById(parentUuid)
                ?: return@runCatching emptyList<UIMessagePart>()
            val selected = conv.messageNodes.mapNotNull { node -> node.messages.getOrNull(node.selectIndex) }
            SubAgentContextDigest.mediaPartsFrom(selected)
        }.getOrElse { emptyList() }
    }

    private suspend fun harvestFinalText(conversationId: Uuid): String {
        // The Conversation persisted by the generation pipeline contains the full message
        // history (messageNodes). Each MessageNode holds parallel branches in
        // `messages: List<UIMessage>` keyed by `selectIndex`. Walk the currently-selected
        // branch and pull text from the last assistant message — that's the sub-agent's
        // final summary.
        //
        // Robustness: if the last assistant message has NO Text part (some models stop
        // after a tool call and emit no closing text), walk back through previous assistant
        // messages and concatenate their Text parts so we don't return empty. Better to
        // surface partial intermediate text than to return "" and lose the sub-agent's
        // work entirely.
        return runCatching {
            val conv = conversationRepo.getConversationById(conversationId) ?: return@runCatching ""
            val selectedMessages = conv.messageNodes.mapNotNull { node ->
                node.messages.getOrNull(node.selectIndex)
            }
            val assistantMessages = selectedMessages.filter { msg ->
                msg.role.name.equals("assistant", ignoreCase = true)
            }
            if (assistantMessages.isEmpty()) return@runCatching ""

            // Try the last assistant message's text first.
            val lastTexts = assistantMessages.last().parts
                .filterIsInstance<UIMessagePart.Text>()
                .joinToString("\n") { it.text }
                .trim()
            if (lastTexts.isNotBlank()) return@runCatching lastTexts

            // Fallback: collect text from all assistant messages (preserve order).
            assistantMessages
                .flatMap { it.parts.filterIsInstance<UIMessagePart.Text>() }
                .joinToString("\n") { it.text }
                .trim()
        }.getOrDefault("")
    }
}
