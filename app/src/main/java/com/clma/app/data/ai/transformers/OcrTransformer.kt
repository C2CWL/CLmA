package com.clma.app.data.ai.transformers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import com.clma.ai.core.MessageRole
import com.clma.ai.provider.Modality
import com.clma.ai.provider.Model
import com.clma.ai.provider.ProviderManager
import com.clma.ai.provider.TextGenerationParams
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import com.clma.common.cache.LruCache
import com.clma.common.cache.SingleFileCacheStore
import com.clma.app.R
import com.clma.app.data.datastore.SettingsStore
import com.clma.app.data.datastore.findModelById
import com.clma.app.data.datastore.findProvider
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File

private const val TAG = "OcrTransformer"

// Hard ceiling on a single OCR/vision describe call. Without it, an OCR model that is
// misconfigured, dead, or itself not vision-capable blocks the whole generation forever.
// On Telegram that wedges the per-chat mutex, so every later message queues until the user
// sends /new — the symptom this bound exists to prevent.
private const val OCR_TIMEOUT_MS = 60_000L

/**
 * Which `file:` image urls in [messages] still need OCR, i.e. are not covered by
 * [isCached]. Pure so the "should we even show a status / do any work" decision can be
 * unit-tested without a real cache.
 */
internal fun selectUncachedImageUrls(
    messages: List<UIMessage>,
    isCached: (String) -> Boolean,
): List<String> = messages
    .asSequence()
    .flatMap { it.parts.asSequence() }
    .filterIsInstance<UIMessagePart.Image>()
    .map { it.url }
    .filter { it.startsWith("file:") }
    .filterNot(isCached)
    .toList()

object OcrTransformer : InputMessageTransformer, KoinComponent {
    private val cache by lazy {
        val context = get<Context>()
        val json = Json { allowStructuredMapKeys = true }
        val store = SingleFileCacheStore(
            file = File(context.cacheDir, "ocr_cache.json"),
            keySerializer = String.serializer(),
            valueSerializer = String.serializer(),
            json = json
        )
        LruCache(
            capacity = 256,
            store = store,
            deleteOnEvict = true,
            preloadFromStore = true,
        )
    }

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        if (ctx.model.inputModalities.contains(Modality.IMAGE)) {
            return messages
        }

        val hasImages = messages.any { message ->
            message.parts.any { it is UIMessagePart.Image && it.url.startsWith("file:") }
        }
        if (!hasImages) return messages

        return withContext(Dispatchers.IO) {
            try {
                val uncachedUrls = selectUncachedImageUrls(messages) { cache.get(it) != null }
                if (uncachedUrls.isNotEmpty()) {
                    val uncachedSet = uncachedUrls.toSet()
                    val triggers = messages.flatMap { message ->
                        message.parts
                            .filterIsInstance<UIMessagePart.Image>()
                            .filter { it.url in uncachedSet }
                            .map { "${message.role}:${it.url}" }
                    }
                    Log.i(
                        TAG,
                        "transform: ${triggers.size} uncached image(s) trigger OCR: ${triggers.take(3)}"
                    )
                    ctx.processingStatus.value = ctx.context.getString(R.string.ocr_status_recognizing)
                }
                messages.map { message ->
                    message.copy(
                        parts = message.parts.map { part ->
                            when {
                                part is UIMessagePart.Image && part.url.startsWith("file:") -> {
                                    UIMessagePart.Text(performOcr(part))
                                }

                                else -> part
                            }
                        }
                    )
                }
            } finally {
                ctx.processingStatus.value = null
            }
        }
    }

    suspend fun performOcr(part: UIMessagePart.Image): String = runCatching {
        // Check cache first
        cache.get(part.url)?.let { cachedResult ->
            Log.i(TAG, "performOcr: Using cached result for ${part.url}")
            return cachedResult
        }

        val settings = get<SettingsStore>().settingsFlow.value
        val model = settings.findModelById(settings.ocrModelId) ?: return "[Image]"
        val providerSetting = model.findProvider(settings.providers) ?: return "[Image]"
        val provider = get<ProviderManager>().getProviderByType(providerSetting)
        val result = withTimeoutOrNull(OCR_TIMEOUT_MS) {
            provider.generateText(
                providerSetting = providerSetting,
                messages = listOf(
                    UIMessage.system(settings.ocrPrompt),
                    UIMessage(
                        role = MessageRole.USER,
                        parts = listOf(UIMessagePart.Image(part.url))
                    )
                ),
                params = TextGenerationParams(
                    model = model,
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies,
                ),
            )
        }
        if (result == null) {
            Log.w(TAG, "performOcr: timed out after ${OCR_TIMEOUT_MS}ms for ${part.url}")
            // Not cached: a timeout is usually transient/config-related, so a later retry
            // should be allowed to reach the model again.
            return "[Image: could not be read — the OCR model did not respond in time]"
        }
        val content = result.message.toText().ifBlank { "[ERROR, OCR failed]" }
        Log.i(TAG, "performOcr: $content")
        val ocrResult = """
            <image_file_ocr>
               $content
            </image_file_ocr>
            * The image_file_ocr tag contains a description of an image that the user uploaded to you, not the user's prompt.
        """.trimIndent()

        // Cache the result
        cache.put(part.url, ocrResult)
        return ocrResult
    }.getOrElse {
        // Let a real cancellation (e.g. the user's /stop) propagate instead of swallowing
        // it into a fake OCR-failure string, which would defeat cooperative cancellation.
        if (it is kotlinx.coroutines.CancellationException) throw it
        "[ERROR, OCR failed: $it]"
    }
}
