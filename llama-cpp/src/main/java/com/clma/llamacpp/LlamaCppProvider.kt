package com.clma.llamacpp

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import com.clma.ai.core.MessageRole
import com.clma.ai.provider.ImageGenerationParams
import com.clma.ai.provider.Model
import com.clma.ai.provider.Provider
import com.clma.ai.provider.ProviderSetting
import com.clma.ai.provider.TextGenerationParams
import com.clma.ai.provider.TextGenerationResult
import com.clma.ai.ui.ImageGenerationItem
import com.clma.ai.ui.StreamChunk
import com.clma.ai.ui.StreamChunkHandler
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import com.clma.locallm.LocalRuntime
import com.clma.locallm.LocalRuntimePreferences
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Turns one [ChatDeltaTracker] channel's deltas into the safe suffix to forward downstream,
 * given that the shared chunk protocol (UIMessage's delta-append fold) can only ever append,
 * never retract, what it has already been sent.
 *
 * A non-reset delta is already a safe suffix and is forwarded unchanged. A reset delta
 * carries the channel's full corrected value instead of a suffix (see [ChatDelta]'s doc); what
 * has already been forwarded downstream cannot be un-sent, so re-sending the corrected value
 * in full would duplicate whatever of it was already shown rather than fix it. Mid-stream
 * ([isFinal] false), forwarding nothing and silently resyncing to the corrected value is the
 * right call: a later, non-reset delta will extend past the corrected value and its suffix
 * carries the correction downstream, so nothing is lost - keeping the stale watermark instead
 * would make every later delta fail its "is this an extension of what's shown" check forever,
 * since the true text no longer contains the stale prefix at all once it has diverged.
 *
 * On the terminal delta ([isFinal] true - the final `isPartial = false` parse, see
 * [ChatDeltaTracker.consume]) there is no later delta to rely on: generation has already
 * stopped, so swallowing a reset there would drop the model's real final text permanently
 * instead of merely delaying its correction. [advance] forwards the full corrected value in
 * that one case instead, accepting a possible duplicate suffix in exchange for never silently
 * losing content the model actually produced.
 */
private class ChannelWatermark {
    private var shown = ""

    fun advance(deltaValue: String, reset: Boolean, isFinal: Boolean): String {
        if (reset) {
            shown = deltaValue
            return if (isFinal) deltaValue else ""
        }
        shown += deltaValue
        return deltaValue
    }
}

/**
 * Streams generation from a locally loaded GGUF through [LlamaCppRuntime]. The prompt, the
 * grammar that constrains tool-call syntax, and the rules for parsing the reply all come from
 * the applied-template blob [LlamaCppRuntime.applyTemplate] returns; this provider treats that
 * blob as opaque, only ever handing it back to [LlamaCppRuntime.generate] and
 * [LlamaCppRuntime.parse], never parsing or rebuilding it.
 *
 * [streamText] resolves [TextGenerationParams.model]'s `modelId` - a bare file name, never a
 * path (see [LocalRuntimePreferences.installedModels]) - to the GGUF's absolute path, loads it
 * via [LlamaCppRuntime.load], and only then templates and generates. [loadedPath] tracks which
 * path is currently loaded so a repeat call for the same model is a no-op: reloading a
 * multi-GB model on every turn would make every message pay the load cost again.
 *
 * [LlamaCppRuntime.generate] blocks the calling thread for the whole generation, so it runs on
 * a child coroutine on [Dispatchers.IO] rather than inline in the flow body: cancelling a
 * coroutine only takes effect at a suspension point, and a blocking native call has none, so a
 * cancelled collector could not otherwise interrupt it. [awaitClose] runs concurrently with
 * that child and calls [LlamaCppRuntime.cancelGeneration] - the only way to interrupt a prefill,
 * per its doc - when the flow is cancelled before the generation finished on its own.
 */
class LlamaCppProvider(
    private val context: Context,
    private val runtime: LlamaCppRuntime,
    private val prefs: LocalRuntimePreferences,
) : Provider<ProviderSetting.LlamaCppLocal> {

    /** Path [runtime] currently has loaded, or null before the first call. Read and set by
     *  [ensureLoaded] so a repeat [streamText] call for the same model skips the reload. */
    private val loadedPath = AtomicReference<String?>(null)

    /** Serializes each [generateText]/[streamText] call's load-then-generate sequence into one
     *  atomic step, held from [ensureModelReady]'s already-loaded check through the generation
     *  that consumes the loaded model. [runtime] holds at most one model at a time, so releasing
     *  the lock right after loading (as a mutex guarding only [ensureModelReady] would) leaves a
     *  window where a second concurrent call - e.g. a post-turn generateTitle and
     *  generateSuggestion firing right after each other, configurable to different model ids -
     *  swaps [runtime] to its own model before the first call's generation reads it, feeding a
     *  mismatched template/tokenizer/grammar into native code. Validates before native code runs,
     *  which holds its own mutex for the whole inference so concurrent callers queue up rather
     *  than racing the loaded model. */
    private val loadMutex = Mutex()
    private val mutableVisionStatus = kotlinx.coroutines.flow.MutableStateFlow(LocalVisionStatus.Unloaded)
    val visionStatus: kotlinx.coroutines.flow.StateFlow<LocalVisionStatus> =
        mutableVisionStatus.asStateFlow()


    override suspend fun listModels(providerSetting: ProviderSetting.LlamaCppLocal): List<Model> =
        providerSetting.models

    override suspend fun generateText(
        providerSetting: ProviderSetting.LlamaCppLocal,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult {
        require(!LocalVisionLifecycle.requiresMedia(params.model.modelId)) {
            "Visual models are recognition tools, not chat models; use recognize_local_media"
        }
        require(ChatRequestMapper.imageUrls(messages).isEmpty()) { "Use recognize_local_media for media recognition" }
        return generateRequest(messages, params)
    }

    /** Tool-only entry point, sharing the chat provider's load/generate lock and runtime. */
    suspend fun recognizeMedia(messages: List<UIMessage>, params: TextGenerationParams): TextGenerationResult {
        require(LocalVisionLifecycle.requiresMedia(params.model.modelId)) { "Choose an installed visual catalog model" }
        return generateRequest(messages, params.copy(tools = emptyList()))
    }

    private suspend fun generateRequest(
        messages: List<UIMessage>, params: TextGenerationParams,
    ): TextGenerationResult = loadMutex.withLock {
        val visionOnly = LocalVisionLifecycle.requiresMedia(params.model.modelId)
        LocalVisionLifecycle.validateRequest(params.model.modelId, messages)
        val temporary = mutableListOf<File>()
        try {
            // Free a resident text model before decoding media or measuring available RAM.
            if (visionOnly) releaseVisionModel()
            if (visionOnly) mutableVisionStatus.value = LocalVisionStatus.Loading
            val expanded = withContext(Dispatchers.IO) { LocalVideoFrames.expand(context, messages, temporary) }
            require(ChatRequestMapper.imageUrls(expanded).size <= 12) { "At most 12 images/keyframes per request" }
            val projector = visionProjector(params)
            if (visionOnly) requireNotNull(projector) { "Install the matching vision projector first" }
            ensureModelReady(expanded, params)
            if (visionOnly) mutableVisionStatus.value = LocalVisionStatus.Recognizing
            // Bound the whole recognition, including native encode. Cancellation also sets the
            // native flag; release below still waits for that native work to leave safely.
            try {
                withTimeout(10 * 60 * 1000L) {
                    generateFromLoadedModel(runtime, expanded, params, projector, ::materializeImage)
                }
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                runtime.cancelGeneration()
                throw IllegalStateException("Local recognition exceeded 10 minutes and was stopped", timeout)
            }
        } finally {
            try {
                if (visionOnly) {
                    mutableVisionStatus.value = LocalVisionStatus.Releasing
                    try {
                        releaseVisionModel()
                        mutableVisionStatus.value = LocalVisionStatus.Unloaded
                    } catch (t: Throwable) {
                        mutableVisionStatus.value = LocalVisionStatus.ReleaseFailed
                        throw t
                    }
                }
            } finally {
                temporary.forEach { it.delete() }
            }
        }
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.LlamaCppLocal,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = flow {
        require(!LocalVisionLifecycle.requiresMedia(params.model.modelId)) {
            "Visual models are recognition tools, not chat models; use recognize_local_media"
        }
        // loadMutex is held across the whole load-then-generate sequence, not just the load -
        // see the mutex's doc comment for why the generation itself must stay inside the lock.
        loadMutex.withLock {
            require(ChatRequestMapper.imageUrls(messages).isEmpty()) { "Use recognize_local_media for media recognition" }
            ensureModelReady(messages, params)
            emitAll(streamFromLoadedModel(runtime, messages, params))
        }
    }

    /** Waits for native generation to stop before freeing its context/projector/model.
     * NonCancellable is essential: a cancelled collector must still release residency. */
    private suspend fun releaseVisionModel() = withContext(NonCancellable + Dispatchers.IO) {
        loadedPath.set(null)
        runtime.unload()
    }

    private suspend fun visionProjector(params: TextGenerationParams): String? {
        val entry = LlamaCppCatalog.ENTRIES.firstOrNull { it.file == params.model.modelId }
        val name = entry?.projectorFile ?: return null
        val model = prefs.installedModels(LocalRuntime.LlamaCpp)[params.model.modelId] ?: return null
        return File(File(model).parentFile, name).takeIf { it.isFile }?.absolutePath
    }

    /** Preserve original pixels and normalize EXIF, using a lossless temporary PNG for mtmd. */
    private fun materializeImage(url: String): File {
        val uri = android.net.Uri.parse(url)
        require(uri.scheme == "content" || uri.scheme == "file" || uri.scheme == null) {
            "Local vision requires a local attachment; download remote images first"
        }
        require(android.os.Build.VERSION.SDK_INT >= 28) { "Local image decoding requires Android 9 or newer" }
        val source = if (uri.scheme == null) android.graphics.ImageDecoder.createSource(File(url))
            else android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = android.graphics.ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
            decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val target = File.createTempFile("vision-", ".png", context.cacheDir)
        try {
            target.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
            return target
        } catch (t: Throwable) {
            target.delete()
            throw t
        } finally {
            bitmap.recycle()
        }
    }

    // The catalog's projector size is an additional residency cost, outside the LLM's
    // KV-cache planner. Image encoder scratch buffers still require device validation.
    private fun visionReserveBytes(params: TextGenerationParams): Long =
        LlamaCppCatalog.ENTRIES.firstOrNull { it.file == params.model.modelId }?.projectorSizeBytes ?: 0L

    /** Resolves [TextGenerationParams.model]'s modelId to a path and loads it into [runtime]
     *  (a no-op when it is already loaded - see [ensureLoaded]). Shared by [generateText] and
     *  [streamText] so the two agree on exactly one load path; both already hold [loadMutex]
     *  before calling this. */
    private suspend fun ensureModelReady(messages: List<UIMessage>, params: TextGenerationParams) {
        val installed = prefs.installedModels(LocalRuntime.LlamaCpp)
        val modelPath = resolveModelPath(installed, params.model.modelId)
        // The load blocks the calling thread on a multi-gigabyte mmap, metadata parse and
        // context allocation, so it runs on Dispatchers.IO rather than whatever dispatcher the
        // caller used - mirrors the previous on-device engine init. Without this,
        // a Main-confined caller like ProviderConnectionTester's rememberCoroutineScope would
        // load the model on the UI thread and ANR.
        withContext(Dispatchers.IO) {
            ensureLoaded(
                runtime = runtime,
                loadedPath = loadedPath,
                path = modelPath,
                tools = ChatRequestMapper.toolDeclarations(params.tools),
                systemPromptBytes = systemPromptBytesOf(messages),
                availableRamBytes = (readAvailableRamBytes() - visionReserveBytes(params)).coerceAtLeast(0L),
            )
        }
    }

    /** Bytes of SYSTEM-role text across [messages] - the figure [LlamaCppRuntime.load] feeds
     *  `ContextPlanner` so the system prompt is accounted for before tools are fit into the
     *  context. */
    private fun systemPromptBytesOf(messages: List<UIMessage>): Int =
        messages
            .filter { it.role == MessageRole.SYSTEM }
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Text>()
            .sumOf { it.text.toByteArray(Charsets.UTF_8).size }

    /** Free RAM to size the context against - the same `ActivityManager.MemoryInfo.availMem`
     *  source `MemoryGuard.canLoad` reads, so the planner and the load-time refusal agree on
     *  what "available" means. */
    private fun readAvailableRamBytes(): Long {
        val info = ActivityManager.MemoryInfo()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.getMemoryInfo(info)
        return info.availMem
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem> = error("llama.cpp does not support image generation")

    companion object {
        private const val DEFAULT_MAX_TOKENS = 2048

        /**
         * Resolves [modelId] - a bare file name, never a path - against [installed], the
         * `filename -> absolutePath` index [LocalRuntimePreferences.installedModels] returns.
         * Throws a user-readable, model-named error rather than letting a missing or stale
         * entry fail opaquely later on - rejects an incomplete entry before streaming.
         */
        internal fun resolveModelPath(installed: Map<String, String>, modelId: String): String {
            val path = installed[modelId]
                ?: throw IllegalStateException("Model $modelId not installed")
            if (!File(path).exists()) {
                throw IllegalStateException(
                    "Model file for \"$modelId\" is no longer present on disk ($path). " +
                        "Delete the model entry in Settings → Providers → Local · llama.cpp " +
                        "and re-download it."
                )
            }
            return path
        }

        /**
         * Loads [path] into [runtime] unless it is already the loaded model. Reloading a
         * multi-GB model on every turn is the obvious wrong implementation, so [loadedPath]
         * is checked first and only set to [path] after a real load succeeds.
         *
         * [LlamaCppRuntime.load] unconditionally unloads whatever was loaded before it
         * attempts [path], so the moment it is called the runtime no longer has
         * [loadedPath]'s old value loaded even if the new load then fails (e.g. an
         * oversized model throwing [ModelTooLargeException], or a corrupt file failing
         * natively). [loadedPath] is cleared to null right before that call so a failed
         * load leaves it reflecting "nothing loaded" rather than a model the runtime has
         * already discarded - otherwise a later call for that still-installed model would
         * see it as already loaded, skip the reload, and hit "no model is loaded" on the
         * very generation call this method exists to prevent.
         *
         * A path match still calls [LlamaCppRuntime.replan]: [tools] and
         * [systemPromptBytes] are this request's, not the ones the model happened to be
         * loaded with, and they can change turn to turn (a different assistant/tool
         * config) even when the model itself does not. Skipping that would leave a stale
         * [ContextPlan.droppedToolNames]/[ContextPlan.reservedInputBytes] in effect for
         * every request after the first against a given model.
         */
        internal suspend fun ensureLoaded(
            runtime: LlamaCppRuntime,
            loadedPath: AtomicReference<String?>,
            path: String,
            tools: List<ToolDeclaration>,
            systemPromptBytes: Int,
            availableRamBytes: Long,
        ) {
            if (loadedPath.get() == path) {
                runtime.replan(tools, systemPromptBytes)
                return
            }
            loadedPath.set(null)
            runtime.load(path, tools, systemPromptBytes, availableRamBytes)
            loadedPath.set(path)
        }

        /**
         * [generateText]'s logic, given a [runtime] that already has the right model loaded
         * (see [ensureLoaded]). Every chunk [streamFromLoadedModel] emits carries an
         * incremental delta, not the full message, so the pieces are summed here rather than
         * just keeping the last one - keeps the last one. Kept as a function
         * of an explicit [runtime] parameter for the same testability reason as
         * [streamFromLoadedModel].
         */
        internal suspend fun generateFromLoadedModel(
            runtime: LlamaCppRuntime,
            messages: List<UIMessage>,
            params: TextGenerationParams,
            projector: String? = null,
            imageResolver: ((String) -> File)? = null,
        ): TextGenerationResult {
            var collected = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()))
            val handler = StreamChunkHandler(params.model)
            streamFromLoadedModel(runtime, messages, params, projector, imageResolver).collect { chunk ->
                collected = handler.handle(collected, chunk)
            }
            return TextGenerationResult(
                id = "llamacpp-${System.currentTimeMillis()}",
                model = params.model.modelId,
                message = collected.last(),
            )
        }

        /**
         * The streaming/chunking logic, given a [runtime] that already has the right model
         * loaded (see [ensureLoaded]). Kept as a function of an explicit [runtime] parameter
         * rather than an instance method reading a provider's own field so it stays
         * unit-testable without a live [Context]/[LocalRuntimePreferences] - see
         * `LlamaCppProviderTest`.
         */
        internal fun streamFromLoadedModel(
            runtime: LlamaCppRuntime,
            messages: List<UIMessage>,
            params: TextGenerationParams,
            projector: String? = null,
            imageResolver: ((String) -> File)? = null,
        ): Flow<StreamChunk> = callbackFlow {
            val streamId = "llamacpp-${System.currentTimeMillis()}"
            // One stable id per channel for the whole turn, so StreamChunkHandler appends
            // every delta into a single Text / Reasoning part - mirrors the old positional
            // append-only merge.
            val textId = "$streamId-text"
            val reasoningId = "$streamId-reasoning"
            // Trim history to the input half of the planned context before templating, so a
            // long conversation drops its oldest turns instead of overflowing the prompt.
            val trimmedMessages = if (projector != null) messages else
                ChatRequestMapper.trimToBudget(messages, runtime.inputBudgetBytes())
            ChatRequestMapper.imageUrls(trimmedMessages)
            val appliedTemplateJson = runtime.applyTemplate(
                ChatRequestMapper.toRequestJson(trimmedMessages, params.tools)
            )
            val tracker = ChatDeltaTracker()
            val accumulated = StringBuilder()
            val finished = AtomicBoolean(false)
            // The chunk protocol downstream (UIMessage's delta-append fold) can only ever
            // append, never retract, so these mirror what has actually been forwarded for
            // each channel - see ChannelWatermark's doc for how that reconciles textReset/
            // reasoningReset.
            val textWatermark = ChannelWatermark()
            val reasoningWatermark = ChannelWatermark()

            // A plain captured lambda, not an extension function: it closes over this
            // callbackFlow's ProducerScope directly, so calling it from inside the doubly-nested
            // onPiece callback below needs no implicit-receiver resolution of its own. isFinal
            // marks the terminal (isPartial = false) call so a reset there can still be
            // forwarded - see ChannelWatermark's doc.
            // Tracks whether a ReasoningDelta has been sent that a ReasoningEnd has not yet
            // closed, so a later delta with text but no reasoning can close it - mirrors the
            // old merge logic's "a delta with no reasoning part closes any open reasoning part".
            var reasoningOpen = false

            val sendDelta: (ChatDelta, Boolean) -> Boolean = { delta, isFinal ->
                val textDelta = textWatermark.advance(delta.textDelta, delta.textReset, isFinal)
                val reasoningDelta = reasoningWatermark.advance(delta.reasoningDelta, delta.reasoningReset, isFinal)
                var sentAny = false
                var allOk = true
                if (reasoningDelta.isNotEmpty()) {
                    sentAny = true
                    reasoningOpen = true
                    allOk = trySend(StreamChunk.ReasoningDelta(reasoningId, reasoningDelta)).isSuccess && allOk
                } else if (reasoningOpen && textDelta.isNotEmpty()) {
                    reasoningOpen = false
                    allOk = trySend(StreamChunk.ReasoningEnd(reasoningId)).isSuccess && allOk
                }
                if (textDelta.isNotEmpty()) {
                    sentAny = true
                    allOk = trySend(StreamChunk.TextDelta(textId, textDelta)).isSuccess && allOk
                }
                delta.completedToolCalls.forEach { call ->
                    sentAny = true
                    allOk = trySend(StreamChunk.ToolCallStart(id = call.id, toolName = call.name)).isSuccess && allOk
                    allOk = trySend(StreamChunk.ToolCallDelta(id = call.id, inputDelta = call.arguments)).isSuccess && allOk
                }
                if (sentAny) allOk else !isClosedForSend
            }

            val worker = launch(Dispatchers.IO) {
                val imageFiles = mutableListOf<File>()
                try {
                    val urls = ChatRequestMapper.imageUrls(trimmedMessages)
                    if (urls.isNotEmpty()) {
                        requireNotNull(projector) { "Install the catalog model and matching vision projector first" }
                        val resolve = requireNotNull(imageResolver) { "Local image resolver is unavailable" }
                        urls.forEach { imageFiles += resolve(it) }
                    }
                    runtime.generate(appliedTemplateJson, params.maxTokens ?: DEFAULT_MAX_TOKENS,
                        projector, imageFiles.map { it.absolutePath }.toTypedArray()) { piece ->
                        accumulated.append(piece)
                        val parsed = runtime.parse(accumulated.toString(), true, appliedTemplateJson)
                        sendDelta(tracker.consume(parsed, isPartial = true), false)
                    }

                    // The final parse is authoritative: it flushes any tool call whose arguments
                    // were still settling when generation stopped (see ChatDeltaTracker.consume).
                    val finalParsed = runtime.parse(accumulated.toString(), false, appliedTemplateJson)
                    sendDelta(tracker.consume(finalParsed, isPartial = false), true)
                    trySend(StreamChunk.Finish())
                } catch (t: Throwable) {
                    close(t)
                } finally {
                    imageFiles.forEach { it.delete() }
                    finished.set(true)
                    close()
                }
            }

            awaitClose {
                if (!finished.get()) {
                    runtime.cancelGeneration()
                }
                worker.cancel()
            }
        }
    }
}
