package me.rerere.rikkahub.subagent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * P2-14b — the sub-agent run envelope, split out of [SubAgentTools] so it is pure.
 *
 * Both encoders below touch nothing but [SubAgentRun] and `kotlinx.serialization.json`, which is
 * what lets them be compiled and tested without Android — the same local harness every P2-14 fix
 * shares — instead of only in CI. [SubAgentTools] keeps the tool surface; this file owns the wire
 * format.
 */

/**
 * One run, as `subagent_dispatch` / `subagent_get` return it.
 *
 * The token and call counters (P2-13, read back from the ledger when the run ends) are reported
 * here so a parent can see what a dispatch actually cost, and — when a ceiling is configured —
 * how much of that ceiling is left (P2-15). `subagent_get` passes no headroom, because the
 * registry carries none, so that view stays byte-for-byte as it was.
 */
internal fun encodeRun(run: SubAgentRun, budgetRemaining: Long? = null): JsonObject = buildJsonObject {
    put("id", run.id)
    put("status", run.status.name)
    put("label", run.label)
    if (run.modelId != null) put("model_id", run.modelId)
    if (run.reasoningLevel != null) put("reasoning_level", run.reasoningLevel)
    put("run_in_background", run.runInBackground)
    put("timeout_seconds", run.timeoutSeconds)
    put("max_trips", run.maxTrips)
    put("started_at_ms", run.startedAtMs)
    if (run.finishedAtMs != null) put("finished_at_ms", run.finishedAtMs)
    if (run.result != null && !run.noResult) {
        put("result", run.result)
    } else if (run.noResult) {
        put("result_suppressed", true)
    }
    if (run.error != null) put("error", run.error)
    put("tokens_in", run.tokensIn)
    put("tokens_out", run.tokensOut)
    // P2-13 — model round trips. Distinct from trip_count, which counts tool-loop trips.
    put("calls", run.usageCalls)
    put("trip_count", run.tripCount)
    // P2-15 — the orchestration ceiling's headroom, measured by the engine at admission and
    // reported only when a ceiling exists (§9.2 #7, second half). Absent means "no budget
    // configured", never "nothing left": a zero headroom is reported as 0.
    if (budgetRemaining != null) put("budget_remaining", budgetRemaining)
    // D3 — how many parent audio/video attachments travelled with this dispatch. Absent
    // means none (the caller did not ask), the same "no value, no key" rule as above.
    if (run.mediaParts > 0) put("media_parts", run.mediaParts)
    if (run.retryCount > 0) put("retry_count", run.retryCount)
}

/**
 * P2-14b — many runs, as `subagent_list` returns them.
 *
 * The list entry used to carry only id / label / status / model / start / trip_count, which made
 * it the one place a run's cost could not be seen: `subagent_get` reported `tokens_in` /
 * `tokens_out` / `calls` since P2-13, but a caller scanning the list had to fetch every run
 * individually to compare them. They are reported here now, with exactly the same names and
 * semantics as [encodeRun], so the two views of a run cannot drift apart.
 */
internal fun encodeRuns(runs: List<SubAgentRun>): JsonArray = buildJsonArray {
    runs.forEach { run ->
        addJsonObject {
            put("id", run.id)
            put("label", run.label)
            put("status", run.status.name)
            if (run.modelId != null) put("model_id", run.modelId)
            put("started_at_ms", run.startedAtMs)
            put("tokens_in", run.tokensIn)
            put("tokens_out", run.tokensOut)
            put("calls", run.usageCalls)
            put("trip_count", run.tripCount)
        }
    }
}
