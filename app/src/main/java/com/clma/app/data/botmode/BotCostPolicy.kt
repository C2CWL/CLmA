package com.clma.app.data.botmode

import com.clma.ai.provider.ProviderSetting
import com.clma.app.data.datastore.Settings
import com.clma.app.data.model.Assistant
import com.clma.app.data.model.isBot
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.uuid.Uuid

/**
 * Cost-saving defaults for Bot-mode bots (one long conversation per bot).
 *
 * Everything here applies ONLY to assistants in the Bot library ([Assistant.isBot]) whose
 * [Assistant.botCostSaving] switch is not explicitly `false`. Classic assistants, and a bot whose
 * switch was turned off, see exactly the settings and behaviour they had before: every function
 * returns its input unchanged (or the classic constant) for them.
 *
 * What it changes for a cost-saving bot, and why:
 * - Automatic compaction on, reusing the existing precision compaction (S2 cache hysteresis:
 *   compact down to 35% of the trigger, so the next compaction - which rewrites the cached prefix
 *   once - comes later; S3/S4 ledger and slot contract). The trigger, the kept tool calls and the
 *   summary target are still the user's global compaction settings.
 * - A per-tool-result token budget ([DEFAULT_TOOL_RESULT_MAX_TOKENS]) when the bot has none set;
 *   the cut text still spills to /tool_outputs so nothing is lost.
 * - Request-only ageing (old screenshots, old user images, masked old read-only tool output) moves
 *   in batches instead of one message per step. Each ageing step rewrites an earlier message,
 *   which invalidates the provider's prompt cache from that message on; batching makes that
 *   happen once every [IMAGE_AGING_BATCH] / [OBSERVATION_MASK_BATCH] items instead of every time,
 *   at the price of keeping up to batch-1 extra items verbatim.
 * - A stable `prompt_cache_key` per conversation, only for the official OpenAI API host (it is a
 *   routing hint there for models before GPT-5.6; other OpenAI-compatible hosts never get it).
 * - An idle pre-compaction that runs the same automatic compaction the next send would run anyway
 *   (see [idleCompactionDue]), so the user does not wait for it.
 */
object BotCostPolicy {
    /**
     * Per-tool-result budget for cost-saving bots without their own value. Same order of magnitude
     * as Codex CLI's sample `tool_output_token_limit = 12000`; only ever tightens the existing
     * 32 KB character spill gate (see GenerationLoop.maybeTruncateToolOutput).
     */
    const val DEFAULT_TOOL_RESULT_MAX_TOKENS = 12_000

    /** Old images are aged this many at a time (classic: 1). */
    const val IMAGE_AGING_BATCH = 4

    /** Old read-only tool outputs are masked this many at a time (classic: 1). */
    const val OBSERVATION_MASK_BATCH = 8

    /**
     * Quiet time after a finished turn before the idle pre-compaction looks at the conversation.
     * Long enough not to race a quick follow-up; any new generation cancels it.
     */
    const val IDLE_COMPACTION_DELAY_MS = 120_000L

    private const val OPENAI_API_HOST = "api.openai.com"
    const val PROMPT_CACHE_KEY_FIELD = "prompt_cache_key"

    /** True when [assistant] is a bot with the cost-saving defaults in effect. */
    fun isActive(assistant: Assistant?): Boolean =
        assistant != null && assistant.isBot && assistant.botCostSaving != false

    /**
     * Settings as a cost-saving bot sees them: automatic and precision compaction on. Returns
     * [settings] itself for everyone else. Idempotent. Never persist the result.
     */
    fun effectiveSettings(settings: Settings, assistant: Assistant?): Settings {
        if (!isActive(assistant)) return settings
        if (settings.enableAutoCompaction && settings.enablePrecisionCompaction) return settings
        return settings.copy(enableAutoCompaction = true, enablePrecisionCompaction = true)
    }

    /** The assistant a generation runs with: fills in the bot default tool-result budget. */
    fun effectiveAssistant(assistant: Assistant): Assistant =
        if (isActive(assistant) && assistant.toolResultMaxTokens == null) {
            assistant.copy(toolResultMaxTokens = DEFAULT_TOOL_RESULT_MAX_TOKENS)
        } else {
            assistant
        }

    fun imageAgingBatch(assistant: Assistant?): Int = if (isActive(assistant)) IMAGE_AGING_BATCH else 1

    fun observationMaskBatch(assistant: Assistant?): Int =
        if (isActive(assistant)) OBSERVATION_MASK_BATCH else 1

    /**
     * How many of the oldest [total] items to age when the newest [keep] stay verbatim, aging in
     * steps of [batch]. With batch 1 this is exactly `total - keep` (the classic behaviour); with a
     * larger batch the count only moves every [batch] new items, so the request prefix before the
     * newest items stays byte-identical between those steps.
     */
    fun batchedAgedCount(total: Int, keep: Int, batch: Int): Int {
        val over = total - keep.coerceAtLeast(0)
        if (over <= 0) return 0
        val step = batch.coerceAtLeast(1)
        return (over / step) * step
    }

    /**
     * The `prompt_cache_key` for this request, or null. Only for a cost-saving bot talking to the
     * official OpenAI API (the field is an OpenAI parameter; a strict OpenAI-compatible gateway
     * might reject an unknown one). One key per conversation: a bot keeps one long conversation,
     * and the key must stay stable for its requests to share a cache.
     */
    fun promptCacheKey(assistant: Assistant?, provider: ProviderSetting, conversationId: Uuid?): String? {
        if (!isActive(assistant) || conversationId == null) return null
        if (provider !is ProviderSetting.OpenAI || !provider.promptCaching) return null
        if (provider.baseUrl.toHttpUrlOrNull()?.host != OPENAI_API_HOST) return null
        return "clma-bot:$conversationId"
    }

    /**
     * Whether a finished turn should arm the idle pre-compaction: a cost-saving bot, automatic
     * compaction on, a normal (not regenerate-range) turn and a persisted conversation.
     */
    fun shouldArmIdleCompaction(
        assistant: Assistant?,
        autoCompactionEnabled: Boolean,
        regeneratingRange: Boolean,
        temporaryConversation: Boolean,
    ): Boolean = isActive(assistant) && autoCompactionEnabled && !regeneratingRange && !temporaryConversation

    /**
     * Idle pre-compaction runs only when the request as it stands already reaches the automatic
     * compaction trigger. The next send adds a user message on top, so that send would compact
     * anyway: running it early costs the same one summary call, only earlier. Below the trigger it
     * never runs, so it never adds a compaction the user would not have had.
     */
    fun idleCompactionDue(estimatedTokens: Int, triggerTokens: Int?): Boolean =
        triggerTokens != null && triggerTokens > 0 && estimatedTokens >= triggerTokens
}
