package me.rerere.rikkahub.data.ai

import kotlin.uuid.Uuid

/**
 * In-memory model routing state: when the current model's generation fails after
 * exhausting all internal retries (transport retry → continuation → restart),
 * the caller asks [nextRouteCandidate] for the first usable model in the user's
 * favourites list that is neither already tried nor in a cooldown window.
 *
 * Cooldowns are ephemeral (process memory only): a 5-minute timeout lets a
 * transiently-failing model recover before it is tried again, and the original
 * model is silently restored on the next user turn (its id is still the
 * conversation/assistant/settings default — routing only overrides the model
 * for a single generation attempt).
 *
 * Pure logic: [nowMs] is injected so tests can control time without touching
 * Android's [android.os.SystemClock].
 */
class ModelRouter(
    // Mutable so tests can advance the clock on the same instance that holds
    // the cooldown state. Production code sets this once and never touches it.
    var nowMs: () -> Long,
) {
    companion object {
        const val DEFAULT_COOLDOWN_MS = 5 * 60 * 1000L // 5 minutes
    }

    // modelId → expiry (epoch-millis from nowMs)
    private val cooldowns = mutableMapOf<Uuid, Long>()

    /**
     * Put [modelId] into cooldown for [durationMs] (default 5 min).
     */
    fun putCooldown(modelId: Uuid, durationMs: Long = DEFAULT_COOLDOWN_MS) {
        cooldowns[modelId] = nowMs() + durationMs
    }

    /**
     * True while [modelId] is still within its cooldown window.
     * Expired entries are lazily removed.
     */
    fun isInCooldown(modelId: Uuid): Boolean {
        val expiry = cooldowns[modelId] ?: return false
        if (nowMs() >= expiry) {
            cooldowns.remove(modelId)
            return false
        }
        return true
    }

    /**
     * Walk [favorites] top-to-bottom and return the first model that is:
     *  - not in [exclude] (the original + any already-tried fallback models)
     *  - not currently in cooldown
     *  - resolvable by [findModel] and accepted by [isUsable]
     *
     * Returns null when no candidate remains — the caller should surface the
     * original error.
     */
    fun <T> nextRouteCandidate(
        favorites: List<Uuid>,
        findModel: (Uuid) -> T?,
        isUsable: (T) -> Boolean,
        exclude: Set<Uuid>,
    ): T? =
        favorites
            .asSequence()
            .filter { it !in exclude && !isInCooldown(it) }
            .map { findModel(it) }
            .filterNotNull()
            .firstOrNull { isUsable(it) }
}
