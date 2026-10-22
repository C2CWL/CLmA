package com.clma.app.data.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.clma.app.AppScope
import com.clma.app.data.datastore.AiLogLevel
import com.clma.app.data.datastore.SettingsStore
import com.clma.ai.provider.ProviderSetting
import com.clma.ai.provider.TextGenerationParams
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart

sealed class AILogging {
    data class Generation(
        val params: TextGenerationParams,
        /** The full request messages; kept only at [AiLogLevel.DEBUG] (empty otherwise). */
        val messages: List<UIMessage>,
        val providerSetting: ProviderSetting,
        val stream: Boolean,
        /** Counts and sizes of the request; always set once stored. */
        val summary: GenerationSummary? = null,
    ) : AILogging()
}

/** Size of a logged request without holding on to its (possibly huge) messages. */
data class GenerationSummary(
    val messageCount: Int,
    val partCount: Int,
    val textChars: Long,
    val imageCount: Int,
    val documentCount: Int,
    val otherMediaCount: Int,
) {
    companion object {
        fun of(messages: List<UIMessage>): GenerationSummary {
            var parts = 0
            var chars = 0L
            var images = 0
            var documents = 0
            var media = 0
            messages.forEach { message ->
                message.parts.forEach { part ->
                    parts++
                    when (part) {
                        is UIMessagePart.Text -> chars += part.text.length
                        is UIMessagePart.Reasoning -> chars += part.reasoning.length
                        is UIMessagePart.Image -> images++
                        is UIMessagePart.Document -> documents++
                        is UIMessagePart.Video, is UIMessagePart.Audio -> media++
                        else -> Unit
                    }
                }
            }
            return GenerationSummary(messages.size, parts, chars, images, documents, media)
        }
    }
}

/**
 * What a log entry keeps at [level]. Below DEBUG only the summary is stored: the manager keeps
 * the last [MAX_LOGS] requests, and each used to pin its whole transformed message list
 * (inlined documents included) in memory for as long as it stayed in the list.
 */
internal fun AILogging.retainedFor(level: AiLogLevel): AILogging = when (this) {
    is AILogging.Generation -> {
        val withSummary = if (summary == null) copy(summary = GenerationSummary.of(messages)) else this
        if (level == AiLogLevel.DEBUG) withSummary else withSummary.copy(messages = emptyList())
    }
}

private const val MAX_LOGS = 32

class AILoggingManager(
    private val settingsStore: SettingsStore,
    appScope: AppScope,
) {
    private val logs = MutableStateFlow<List<AILogging>>(emptyList())
    private val logLevel = MutableStateFlow(settingsStore.settingsFlow.value.aiLogLevel)

    init {
        appScope.launch {
            settingsStore.settingsFlow
                .map { it.aiLogLevel }
                .collectLatest { logLevel.value = it }
        }
    }

    fun getLogs(): StateFlow<List<AILogging>> = logs

    fun getLogLevel(): StateFlow<AiLogLevel> = logLevel

    suspend fun setLogLevel(level: AiLogLevel) {
        settingsStore.update { it.copy(aiLogLevel = level) }
    }

    fun addLog(log: AILogging) {
        val level = logLevel.value
        if (level == AiLogLevel.OFF) return
        logs.value = logs.value + log.retainedFor(level)
        if (logs.value.size > MAX_LOGS) {
            logs.value = logs.value.drop(1)
        }
    }

    fun clearLogs() {
        logs.value = emptyList()
    }
}
