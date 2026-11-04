package com.clma.app.data.ai

import com.clma.ai.provider.TextGenerationParams
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import com.clma.app.AppScope
import com.clma.app.data.datastore.AiLogLevel
import com.clma.app.data.datastore.Settings
import com.clma.app.data.datastore.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

sealed class AILogging {
    data class Generation(
        val modelId: String,
        /** Used to compute size at ingestion, never retained by the manager. */
        val messages: List<UIMessage>,
        val stream: Boolean,
        /** Counts and sizes of the request; always set once stored. */
        val summary: GenerationSummary? = null,
        val context: ContextInspection? = null,
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
 * Raw request messages are released at every level. DEBUG may retain only a bounded, redacted
 * inspection for the latest request; INFO retains measurements without content previews.
 */
internal fun AILogging.retainedFor(level: AiLogLevel, contextInspectionEnabled: Boolean): AILogging = when (this) {
    is AILogging.Generation -> {
        val withSummary = if (summary == null) copy(summary = GenerationSummary.of(messages)) else this
        // The inspection already contains a bounded, redacted view. Never retain raw messages,
        // provider credentials, tool execution closures or custom request headers in diagnostics.
        withSummary.copy(
            messages = emptyList(),
            context = when {
                !contextInspectionEnabled -> null
                level == AiLogLevel.DEBUG -> context
                else -> context?.withoutPreviews()
            },
        )
    }
}

private const val MAX_LOGS = 32

class AILoggingManager internal constructor(
    settings: StateFlow<Settings>,
    private val updateSettings: suspend ((Settings) -> Settings) -> Unit,
    appScope: CoroutineScope,
) {
    constructor(settingsStore: SettingsStore, appScope: AppScope) : this(
        settingsStore.settingsFlow,
        { transform -> settingsStore.update(transform) },
        appScope,
    )
    private val retentionLock = Any()
    private val logs = MutableStateFlow<List<AILogging>>(emptyList())
    private val logLevel = MutableStateFlow(settings.value.aiLogLevel)

    private val contextInspectionEnabled = MutableStateFlow(settings.value.contextInspectionEnabled)

    init {
        appScope.launch {
            settings
                .map { it.aiLogLevel to it.contextInspectionEnabled }
                .distinctUntilChanged()
                .collect { (level, inspectionEnabled) ->
                    synchronized(retentionLock) {
                        logLevel.value = level
                        contextInspectionEnabled.value = inspectionEnabled
                        logs.value = if (level == AiLogLevel.OFF) {
                            emptyList()
                        } else {
                            logs.value.map { it.retainedFor(level, inspectionEnabled) }
                        }
                    }
                }
        }
    }

    fun getLogs(): StateFlow<List<AILogging>> = logs

    fun getLogLevel(): StateFlow<AiLogLevel> = logLevel

    fun getContextInspectionEnabled(): StateFlow<Boolean> = contextInspectionEnabled

    suspend fun setContextInspectionEnabled(enabled: Boolean) {
        updateSettings { it.copy(contextInspectionEnabled = enabled) }
    }

    suspend fun setLogLevel(level: AiLogLevel) {
        updateSettings { it.copy(aiLogLevel = level) }
    }

    fun recordGeneration(
        params: TextGenerationParams,
        requestMessages: List<UIMessage>,
        stream: Boolean,
        memoryPrompt: String,
    ) {
        if (logLevel.value == AiLogLevel.OFF) return
        // Diagnostics must not prevent generation if a provider-defined tool schema fails.
        val inspection = if (contextInspectionEnabled.value) runCatching {
            ContextInspection.capture(
                requestMessages, params.tools, memoryPrompt,
                includePreviews = logLevel.value == AiLogLevel.DEBUG,
            )
        }.getOrNull() else null
        addLog(AILogging.Generation(
            modelId = params.model.modelId,
            messages = requestMessages,
            stream = stream,
            context = inspection,
        ))
    }

    fun addLog(log: AILogging) {
        synchronized(retentionLock) {
            val level = logLevel.value
            if (level == AiLogLevel.OFF) return
            val older = logs.value.map { entry ->
                when (entry) {
                    is AILogging.Generation -> entry.copy(context = entry.context?.withoutPreviews())
                }
            }
            logs.value = (older + log.retainedFor(level, contextInspectionEnabled.value)).takeLast(MAX_LOGS)
        }
    }

    fun clearLogs() {
        synchronized(retentionLock) {
            logs.value = emptyList()
        }
    }
}
