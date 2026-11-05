package com.clma.ai.provider

import com.clma.ai.core.ReasoningLevel
import com.clma.ai.registry.ModelRegistry
import java.net.URI

/**
 * Levels the UI should offer for a given provider/model, derived from how each
 * provider maps [ReasoningLevel] onto API fields.
 *
 * Rules:
 * - [ReasoningLevel.AUTO] is always included.
 * - [ReasoningLevel.OFF] only when the provider can actually disable thinking
 *   (distinct API value, not collapsed onto another effort).
 * - Levels that collapse onto the same API value are represented once (lowest
 *   ordinal in the collapse group).
 * - Unknown provider / null → full [ReasoningLevel.entries] list.
 */
fun supportedReasoningLevels(
    provider: ProviderSetting?,
    model: Model? = null,
): List<ReasoningLevel> {
    if (provider == null) return ReasoningLevel.entries.toList()
    val modelId = model?.modelId.orEmpty()
    return when (provider) {
        is ProviderSetting.Codex -> codexLevels()
        is ProviderSetting.Grok -> grokLevels()
        is ProviderSetting.Claude -> claudeLevels()
        is ProviderSetting.Google,
        is ProviderSetting.GeminiOAuth -> googleLevels(modelId)
        is ProviderSetting.OpenAI -> openAiLevels(provider, modelId)
        // Local / on-device providers either ignore reasoning or have no graded
        // effort knob — keep AUTO so the control still opens.
        is ProviderSetting.AICore,
        is ProviderSetting.LiteRtLocal,
        is ProviderSetting.LlamaCppLocal -> listOf(ReasoningLevel.AUTO)
    }
}

/**
 * Map a saved level onto the nearest entry in [supported] (by enum ordinal).
 * Returns [current] unchanged when it is already supported or [supported] is empty.
 */
fun nearestSupportedReasoningLevel(
    current: ReasoningLevel,
    supported: List<ReasoningLevel>,
): ReasoningLevel {
    if (supported.isEmpty() || current in supported) return current
    return supported.minBy { kotlin.math.abs(it.ordinal - current.ordinal) }
}

private fun keep(vararg levels: ReasoningLevel): List<ReasoningLevel> {
    val set = linkedSetOf(ReasoningLevel.AUTO)
    levels.forEach { set.add(it) }
    return ReasoningLevel.entries.filter { it in set }
}

private fun codexLevels(): List<ReasoningLevel> {
    // AUTO→null, OFF→none, LOW/MED/HIGH/XHIGH distinct; MAX collapses to xhigh.
    return keep(
        ReasoningLevel.OFF,
        ReasoningLevel.AUTO,
        ReasoningLevel.LOW,
        ReasoningLevel.MEDIUM,
        ReasoningLevel.HIGH,
        ReasoningLevel.XHIGH,
    )
}

private fun grokLevels(): List<ReasoningLevel> {
    // OFF and AUTO both omit effort (cannot turn thinking off). XHIGH/MAX→high.
    return keep(
        ReasoningLevel.AUTO,
        ReasoningLevel.LOW,
        ReasoningLevel.MEDIUM,
        ReasoningLevel.HIGH,
    )
}

private fun claudeLevels(): List<ReasoningLevel> {
    // OFF→disabled; AUTO→adaptive; else adaptive+effort (low/medium/high/xhigh/max).
    return ReasoningLevel.entries.toList()
}

private fun googleLevels(modelId: String): List<ReasoningLevel> {
    val isGemini3 = ModelRegistry.GEMINI_3_SERIES.match(modelId = modelId)
    val isGemini25Pro = modelId.contains(Regex("2\\.5.*pro", RegexOption.IGNORE_CASE))
    return when {
        isGemini3 -> {
            // OFF→MINIMAL; LOW/MEDIUM distinct; HIGH/XHIGH/MAX→HIGH.
            keep(
                ReasoningLevel.OFF,
                ReasoningLevel.AUTO,
                ReasoningLevel.LOW,
                ReasoningLevel.MEDIUM,
                ReasoningLevel.HIGH,
            )
        }
        isGemini25Pro -> {
            // OFF is a no-op on 2.5 Pro (cannot disable). Budgets otherwise distinct.
            keep(
                ReasoningLevel.AUTO,
                ReasoningLevel.LOW,
                ReasoningLevel.MEDIUM,
                ReasoningLevel.HIGH,
                ReasoningLevel.XHIGH,
                ReasoningLevel.MAX,
            )
        }
        else -> ReasoningLevel.entries.toList()
    }
}

private fun openAiLevels(provider: ProviderSetting.OpenAI, modelId: String): List<ReasoningLevel> {
    if (provider.useResponseApi) {
        // ResponseAPI: AUTO omits effort; every other level sends its own effort string.
        return ReasoningLevel.entries.toList()
    }
    val host = runCatching { URI(provider.baseUrl).host.orEmpty() }.getOrDefault("")
    return when (host) {
        "openrouter.ai", "opencode.ai" -> ReasoningLevel.entries.toList()

        "dashscope.aliyuncs.com" -> ReasoningLevel.entries.toList()

        // Boolean on/off only — no effort granularity.
        "ark.cn-beijing.volces.com",
        "chat.intern-ai.org.cn",
        "api.siliconflow.cn",
        "aiping.cn",
        "open.bigmodel.cn",
        "api.xiaomimimo.com",
        "token-plan-cn.xiaomimimo.com",
        "api.moonshot.cn",
        -> keep(ReasoningLevel.OFF, ReasoningLevel.AUTO)

        "api.mistral.ai" -> keep(ReasoningLevel.AUTO)

        "api.deepseek.com" -> {
            // OFF disabled; AUTO no effort; LOW→low; MEDIUM+HIGH→high; XHIGH→xhigh; MAX→max.
            keep(
                ReasoningLevel.OFF,
                ReasoningLevel.AUTO,
                ReasoningLevel.LOW,
                ReasoningLevel.MEDIUM,
                ReasoningLevel.XHIGH,
                ReasoningLevel.MAX,
            )
        }

        "integrate.api.nvidia.com" -> {
            if ("deepseek-v4" in modelId.lowercase()) {
                // OFF→none; LOW/MED/HIGH→high; XHIGH/MAX→max.
                keep(
                    ReasoningLevel.OFF,
                    ReasoningLevel.AUTO,
                    ReasoningLevel.LOW,
                    ReasoningLevel.XHIGH,
                )
            } else {
                // OFF remaps to "low" (cannot disable). Others send their effort.
                keep(
                    ReasoningLevel.AUTO,
                    ReasoningLevel.LOW,
                    ReasoningLevel.MEDIUM,
                    ReasoningLevel.HIGH,
                    ReasoningLevel.XHIGH,
                    ReasoningLevel.MAX,
                )
            }
        }

        else -> {
            // OpenAI official Chat Completions: OFF remaps to "low"; docs list
            // low/medium/high, but the code still forwards xhigh/max as-is.
            keep(
                ReasoningLevel.AUTO,
                ReasoningLevel.LOW,
                ReasoningLevel.MEDIUM,
                ReasoningLevel.HIGH,
                ReasoningLevel.XHIGH,
                ReasoningLevel.MAX,
            )
        }
    }
}
