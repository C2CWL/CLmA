package com.clma.app.data.vector

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.clma.llamacpp.LlamaCppEmbedder
import com.clma.llamacpp.LlamaCppEmbeddingCatalog
import com.clma.llamacpp.LlamaCppEmbeddingEntry
import com.clma.locallm.litert.LitertEmbedBackend
import com.clma.locallm.litert.LitertEmbeddingCatalog
import com.clma.locallm.litert.LitertEmbeddingEntry
import com.clma.locallm.litert.LitertLmEmbedder
import java.io.File

/**
 * The app's one door to the embedding model.
 *
 * One model at a time, on purpose: it is a few hundred megabytes resident and a device has one
 * pair of hands. Everything that wants a vector - the memory index today, tools and files later -
 * comes through here, so the "which model, is it loaded, what width is it" questions have exactly
 * one answer, and a model swap invalidates the index in one place rather than in each caller.
 *
 * The model is resolved from what is installed rather than from a baked-in choice: the file names
 * in [LlamaCppEmbeddingCatalog] are what the ordinary local-model page writes into
 * `local-models/llamacpp`, so installing the recommended GGUF is all it takes. See
 * [EmbeddingModelRules.pick] for how an explicit setting and that fallback interact.
 *
 * There are two backends behind this door. The local one is the default and came first: a GGUF
 * file, resident, nothing leaving the device. The cloud one - off unless the user turns it on and
 * picks a model - replaces it wholesale rather than supplementing it, because every row of the
 * index stores *which* model produced it ([ActiveModel.modelId]) and mixing two models' vectors in
 * one index is the one mistake that would keep returning plausible answers. Switching between the
 * two therefore invalidates the index by construction, and the search layer already says so.
 *
 * Whoever embeds something has to size it for the *loaded* model's window
 * ([ActiveModel.contextTokens]), not for a number written down here: the models on offer do not
 * agree on one, and the runtime refuses - rather than silently truncates - a text that does not
 * fit. See [ChunkSpec.fitting].
 */
class EmbeddingService(
    /**
     * Where installed embedding models live - one directory per runtime, see [EmbeddingModelFiles].
     * A lambda, so tests never touch the filesystem.
     *
     * A *list* rather than one directory because which engine runs is decided by the extension of
     * the file that resolves: `.gguf` goes to llama.cpp, `.litertlm` to LiteRT-LM. The file is a
     * name ([EmbeddingModelRules.pick] reasons in names), so it has to be looked for in both.
     */
    private val modelDirs: () -> List<File>,
    /** The user's explicit choice, if any. */
    private val configuredFileName: suspend () -> String?,
    private val embedder: LlamaCppEmbedder = LlamaCppEmbedder(),
    /**
     * The LiteRT-LM engine, used when the resolved file is a `.litertlm`.
     *
     * Two engines behind this one door rather than one abstraction over both: they share nothing
     * except the idea of producing a vector - one owns a GGUF context, the other a compiled
     * `.litertlm` - and the choice between them is a property of the *installed file*, not of a
     * setting, so there is nothing for an abstraction to decide.
     */
    private val litert: LitertLmEmbedder = LitertLmEmbedder(),
    /** The curated `.litertlm` entry for a file name, which carries its signature and prefixes. */
    private val litertEntryFor: (String) -> LitertEmbeddingEntry? = LitertEmbeddingCatalog::entryFor,
    /**
     * The user's signature override, or null to use the catalogue's.
     *
     * Asked on every load rather than captured, like [cloudChoice]: the value is what decides the
     * engine's arena and its longest accepted text, so it must not lag a settings change by a
     * launch. The catalogue's own choice is what an untouched install answers.
     */
    private val configuredSignature: suspend () -> Int? = { null },
    /**
     * The user's accelerator preference for a `.litertlm`, or null when they have not expressed one.
     *
     * Null rather than a boolean so "not chosen" survives a restart: the default is the CPU, and a
     * user who has answered keeps their answer. See [EmbeddingAcceleratorRules].
     */
    private val configuredGpu: suspend () -> Boolean? = { null },
    /**
     * The order an untouched install picks from, across both runtimes.
     *
     * [EmbeddingCatalog] rather than the GGUF catalogue alone: a `.litertlm` can be the installed
     * model, and a fallback order that only knew about GGUFs would answer "nothing to embed with"
     * on a phone that has one.
     */
    private val curatedOrder: List<String> = EmbeddingCatalog.curatedOrder,
    /** Prefix lookup, injectable so a test can exercise the behaviour without the catalogue. */
    private val entryFor: (String) -> LlamaCppEmbeddingEntry? = LlamaCppEmbeddingCatalog::entryFor,
    /**
     * The cloud model to embed with, when the user chose one this build can call.
     *
     * Asked on every load rather than captured once, so turning the cloud backend on takes effect
     * on the next round of indexing instead of the next launch. Null means local: that is what an
     * untouched install answers, and what a cloud selection that cannot be used falls back to.
     */
    private val cloudChoice: suspend () -> CloudEmbeddingCandidate? = { null },
    /** The transport a cloud model is called through. Unused while [cloudChoice] answers null. */
    private val cloud: CloudEmbeddingCaller? = null,
) {

    /**
     * The loaded model: what it is, how wide its vectors are, and how many tokens it can take.
     *
     * The task prefixes ride along because they are a property of the *loaded file*, and a caller
     * that embeds a document without knowing them would store a vector from the wrong side of the
     * model's contrastive pair - silently, since nothing about that fails.
     *
     * [contextTokens] is the model's own window, and it is what a caller must size its inputs by:
     * the runtime refuses a longer text, and the models people actually install do not agree on a
     * window (8K here, 32K there, 512 for a small BERT).
     */
    data class ActiveModel(
        val fileName: String,
        val dim: Int,
        val contextTokens: Int,
        val queryPrefix: String = "",
        val documentPrefix: String = "",
        /**
         * True when LiteRT-LM is the engine behind this, false when it is llama.cpp.
         *
         * Recorded on the loaded model rather than re-derived from the file name at each call,
         * because the two engines have separate state and [release] has to free the right one -
         * freeing the wrong one leaves a few hundred megabytes resident with nothing referencing it.
         */
        val litert: Boolean = false,
        /**
         * True when this model embeds images as images.
         *
         * The one fact an image indexer needs before it decides *how* to handle a picture: embed
         * it, or feed the model whatever someone else read out of it. False for every GGUF here,
         * for a cloud model, and for a text-only `.litertlm`.
         */
        val vision: Boolean = false,
        /**
         * True when this model embeds sound as sound.
         *
         * Same contract as [vision], and deliberately a separately named flag rather than one
         * "multimodal" boolean: the two encoders are separate modules that can be present in any
         * combination, and an audio indexer that had to guess from a vision flag would either skip
         * a capable model or hand bytes to one that cannot take them.
         */
        val audio: Boolean = false,
        /**
         * The cloud model behind this, or null when it is the local file.
         *
         * [dim] is 0 when this is set: the width is the provider's business and is only known once
         * a response has come back, so nothing sizes anything by it. The identity in [modelId] is
         * what keeps two models' vectors apart, and that is known before the first call.
         */
        val cloud: CloudEmbeddingCandidate? = null,
        /**
         * The accelerator the vectors come from, lower-case, or null when it is not a factor.
         *
         * Set for a `.litertlm` (from the engine that actually opened), null for llama.cpp and for
         * a cloud model.
         *
         * It belongs in [modelId] rather than in a comment because the two accelerators are not the
         * same function: the same sentence comes back with cosine 0.9997 between them, small enough
         * that nothing downstream would ever notice - the index would just rank slightly worse for
         * everything embedded before the switch, and only on the device where it happened. Leaving
         * it out is what the switch would otherwise do, because it changes no file name and every
         * staleness check here compares *this* string. With it in, flipping the switch is the
         * ordinary "the index was built with a different model" case, which every source already
         * detects and rebuilds.
         */
        val accelerator: String? = null,
    ) {
        /** What goes into `vector_chunks.model_id`. */
        val modelId: String
            get() = EmbeddingModelRules.modelIdOf(fileName) + accelerator?.let { "#$it" }.orEmpty()

        /** True when the vectors are computed off the device, from text that is sent to do it. */
        val isCloud: Boolean get() = cloud != null
    }

    private val lock = Mutex()

    /**
     * Serialises forward passes. The native engine runs one inference at a time, and search can
     * now legitimately run while an index round is embedding; two threads in the interpreter is
     * undefined behaviour, not merely slower.
     */
    private val forwardLock = Mutex()

    @Volatile
    private var active: ActiveModel? = null

    /**
     * The [Request.key] that produced [active], or null when nothing is loaded.
     *
     * Kept beside the model rather than on it: [ActiveModel] describes the answer, which is what
     * every caller cares about, while this describes the question. Only read and written under
     * [lock], by [loadLocked] and [releaseHoldingLock].
     */
    private var activeKey: String? = null

    /**
     * Runs [block] off the caller's thread, and is the *only* way into the native engine.
     *
     * Loading a model reads a few hundred megabytes off disk and compiles it; embedding a batch
     * runs a forward pass per document. Both block whichever thread they are started on, for as
     * long as they take. The callers are spread across the app and several of them are reliably on
     * the main thread: every index coordinator is handed `AppScope`, whose dispatcher is
     * `Dispatchers.Main` (see `RikkaHubApp.AppScope`), and `ChatService.initializeConversation`
     * fires the memory and library syncs from there every time a conversation is opened. Measured
     * on the device, a cold sync of the memory directory is tens of seconds of work -- and it
     * arrived as an ANR of `RouteActivity` ("Waited 5000ms for MotionEvent"), not as a slow screen.
     *
     * The dispatch therefore lives *here*, once, instead of at each call site: a caller cannot
     * forget it, because there is no other path to [loadLocked]'s file read, [embedDocuments] /
     * [embedQuery]'s forward pass, or [releaseHoldingLock]'s teardown. [Dispatchers.IO] rather than
     * [Dispatchers.Default] because a native call blocks its thread rather than merely using it: a
     * load can hold one for many seconds, which is not something to do to the CPU-bound pool.
     *
     * Callers that used to await an embedding on their own thread now suspend instead, which is
     * what a search or a background index round wants.
     */
    private suspend fun <T> onWorker(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { block() }

    /**
     * Loads the model if it is not loaded yet. Returns null when there is nothing usable to
     * embed with, which callers treat as "semantic search is off", not as an error.
     *
     * A model that fails to load is *not* remembered as failed: the next call tries again, so
     * freeing memory or re-downloading a truncated file fixes the situation without a restart.
     */
    suspend fun ensureLoaded(): ActiveModel? = forwardLock.withLock { ensureLoadedLocked() }

    /**
     * The body of [ensureLoaded], for callers that already hold [forwardLock].
     *
     * Loading may have to RELEASE the resident engine first (a settings flip, a model swap). A batch
     * index holds that lock for its whole run, so an [ensureLoaded] arriving between two of its
     * chunks used to unload the engine underneath the batch: an exception mid-index, or — for two
     * same-dimension models — vectors from two different spaces written under one model id.
     */
    private suspend fun ensureLoadedLocked(): ActiveModel? = onWorker { loadLocked() }

    /**
     * What the settings and the disk say should be running, resolved without opening anything.
     *
     * This is a step of its own because it is the cheap half - one settings read, one directory
     * listing, one catalogue lookup - and because it is the half that decides whether the expensive
     * half has to happen at all. Every index round and every search arrives through [ensureLoaded],
     * so "what should be loaded" has to be re-answered each time; answering it once at first use is
     * what made a model picked, a GPU switch flipped or a cloud backend turned on take effect at the
     * next *launch* rather than the next round.
     */
    private data class Request(
        val cloud: CloudEmbeddingCandidate?,
        val fileName: String,
        val file: File?,
        val signature: Int?,
        val accelerator: LitertEmbedBackend?,
    ) {
        /**
         * A cheap identity of the *request* - compared against the request the resident engine came
         * from, to decide whether it is still the right one.
         *
         * Deliberately not [ActiveModel.modelId]: that one is about the vectors and is written into
         * every row, while this one also covers the signature, which changes the window without
         * changing the vector space, and so should reload the engine without invalidating an index.
         */
        val key: String
            get() = cloud?.let { CloudEmbeddingRules.indexModelId(it) }
                ?: listOf(fileName, signature?.toString().orEmpty(), accelerator?.name.orEmpty())
                    .joinToString("|")
    }

    /** The body of [ensureLoaded]; reachable only through [onWorker]. */
    private suspend fun loadLocked(): ActiveModel? = lock.withLock {
        val request = resolve() ?: run {
            // Nothing usable is selected - a deleted file, an empty models directory, or a cloud
            // choice that cannot be called with nothing local to fall back to. Drop whatever is
            // resident rather than keep embedding with a model the user has moved away from.
            releaseHoldingLock()
            return@withLock null
        }
        if (active != null && activeKey == request.key) return@withLock active
        releaseHoldingLock()

        val loaded = when {
            request.cloud != null -> ActiveModel(
                fileName = CloudEmbeddingRules.indexModelId(request.cloud),
                dim = 0,
                contextTokens = CloudEmbeddingRules.contextTokens(request.cloud),
                cloud = request.cloud,
            )

            request.accelerator != null -> loadLitert(request)
            else -> loadLlamaCpp(request)
        } ?: return@withLock null
        active = loaded
        activeKey = request.key
        loaded
    }

    /**
     * Which model should be loaded right now, or null when nothing usable is selected.
     *
     * Cheap by construction: settings, a directory listing, a catalogue lookup. Nothing here opens
     * an engine, which is what lets [loadLocked] ask it on every call.
     */
    private suspend fun resolve(): Request? {
        // The cloud choice is checked first and answers without a request: which model to use is a
        // setting, and a "load" that had to reach the network would make every round of background
        // indexing wait on one.
        cloudChoice()?.let { candidate ->
            return Request(
                cloud = candidate,
                fileName = CloudEmbeddingRules.indexModelId(candidate),
                file = null,
                signature = null,
                accelerator = null,
            )
        }

        val dirs = modelDirs()
        val installed = dirs.flatMap { dir ->
            dir.listFiles()?.filter { it.isFile }?.map { it.name }.orEmpty()
        }
        val fileName = EmbeddingModelRules.pick(
            configured = configuredFileName(),
            installed = installed,
            curatedOrder = curatedOrder,
        ) ?: return null

        val file = dirs.asSequence().map { File(it, fileName) }.firstOrNull { it.isFile } ?: return null
        if (!EmbeddingModelRules.isLitert(fileName)) {
            return Request(
                cloud = null,
                fileName = fileName,
                file = file,
                signature = null,
                accelerator = null,
            )
        }

        val entry = litertEntryFor(fileName) ?: return null
        // The user's override wins, but only if it is one the file could plausibly ship: the engine
        // rejects a signature the file does not have, and a typo in a preference is not worth a
        // failed load.
        val signature = configuredSignature()
            ?.takeIf { it in LitertEmbeddingCatalog.SIGNATURES }
            ?: entry.signature
        // CPU unless the user asked for the GPU. The loader downgrades even that if the engine will
        // not open on it, so the accelerator recorded here describes what ran, not what was asked
        // for - see EmbeddingAcceleratorRules and LitertLmEmbedder.load.
        val accelerator = if (EmbeddingAcceleratorRules.useGpu(configuredGpu())) {
            LitertEmbedBackend.GPU
        } else {
            LitertEmbedBackend.CPU
        }
        return Request(
            cloud = null,
            fileName = fileName,
            file = file,
            signature = signature,
            accelerator = accelerator,
        )
    }

    /**
     * Opens a `.litertlm`.
     *
     * The signature and the prefixes come from the *catalogue*, not from the file: a `.litertlm`
     * carries neither, and both matter in ways that fail quietly. The signature is what bounds
     * every text we will ever hand this engine (it refuses a longer one), and the prefixes are what
     * tell the model which side of its contrastive pair a text is on. An uncurated `.litertlm` is
     * therefore refused rather than guessed at - a wrong window fails loudly, but a missing prefix
     * only ranks worse, which is exactly the kind of damage nothing downstream can detect.
     */
    private suspend fun loadLitert(request: Request): ActiveModel? {
        val entry = litertEntryFor(request.fileName) ?: return null
        val info = litert.load(
            path = request.file!!.absolutePath,
            signature = request.signature!!,
            backend = request.accelerator!!,
            // The probe must mimic the real indexing load: with a bare space the 740M passes
            // on the GPU delegate and only returns NaN once genuine Chinese text shows up at
            // index time (verified 2026-10-18). A prefixed, realistic sentence makes the
            // load-time probe fail there instead, so the engine falls back to the CPU.
            probeText = entry.documentPrefix + LITERT_PROBE_TEXT,
        )
        return ActiveModel(
            fileName = request.fileName,
            dim = info.dim,
            // The loaded signature, not the model's headline context: see the catalogue.
            contextTokens = info.signature,
            queryPrefix = entry.queryPrefix,
            documentPrefix = entry.documentPrefix,
            litert = true,
            vision = entry.vision,
            audio = entry.audio,
            // What the engine actually came up on, so the identity of every row it writes reflects
            // the accelerator that produced it.
            accelerator = info.accelerator,
        )
    }

    private suspend fun loadLlamaCpp(request: Request): ActiveModel {
        val info = embedder.load(request.file!!.absolutePath)
        val entry = entryFor(request.fileName)
        return ActiveModel(
            fileName = request.fileName,
            dim = info.dim,
            // The model's own window, not the one we asked the context for: a small BERT is
            // trained on 512 tokens and the runtime clamps to that, so sizing inputs by anything
            // larger is what would make the next call fail.
            contextTokens = info.nCtxTrain.takeIf { it > 0 }
                ?: LlamaCppEmbedder.DEFAULT_CONTEXT_TOKENS,
            queryPrefix = entry?.queryPrefix.orEmpty(),
            documentPrefix = entry?.documentPrefix.orEmpty(),
        )
    }

    /**
     * Embeds the *documents* that get indexed - memory notes, library files, past turns, tool
     * entries - in order, with the model's document-side task prefix applied.
     *
     * Throws when no model is available; callers that can live without embeddings check
     * [ensureLoaded] first and skip instead.
     */
    suspend fun embedDocuments(texts: List<String>): List<FloatArray> = forwardLock.withLock {
        onWorker {
            val model = ensureLoadedLocked() ?: error("no embedding model is installed")
            if (model.isCloud) embedCloud(model, texts)
            else texts.map { text -> embedWith(model, model.documentPrefix + text) }
        }
    }

    /**
     * Whether the model in effect can embed a picture *as a picture*.
     *
     * The answer is what decides how an image gets indexed: true means the bytes go straight to the
     * engine, false means the caller has to go through whatever read the image for it (on-device
     * text recognition, or a description from a vision chat model). That fallback is always worse -
     * it can only index what the reader happened to notice - so it is deliberately a *degradation*
     * rather than a parallel path, and the caller has to ask before choosing.
     *
     * False for a cloud model, whatever it is: this is about the local engine's own ability, and a
     * provider's embedding endpoint takes text.
     */
    suspend fun canEmbedImages(): Boolean = ensureLoaded()?.vision == true

    /**
     * Embeds one image, L2-normalised, with no task prefix (the model card's prefixes are for text).
     *
     * Throws when the model in effect cannot see - the caller is expected to have asked
     * [canEmbedImages] first and taken the fallback path otherwise.
     */
    suspend fun embedImage(bytes: ByteArray): FloatArray = forwardLock.withLock {
        onWorker {
            val model = ensureLoadedLocked() ?: error("no embedding model is installed")
            require(model.vision) { "the embedding model in effect cannot embed images" }
            if (model.isCloud) error("a cloud embedding model cannot embed images")
            val vector = litert.embedImage(bytes)
            require(vector.size == model.dim) {
                "the model returned ${vector.size} values, expected ${model.dim}"
            }
            vector
        }
    }

    /**
     * Whether the model in effect can embed a clip of sound *as sound*.
     *
     * There is no fallback behind this one: nothing in the app transcribes audio, so false means
     * the file is not indexed at all. Callers therefore have to ask before reading minutes of a
     * file they cannot use.
     */
    suspend fun canEmbedAudio(): Boolean = ensureLoaded()?.audio == true

    /**
     * Embeds one clip of sound, L2-normalised, with no task prefix.
     *
     * [bytes] is a RIFF/WAVE of 16 kHz mono 16-bit PCM: the model's own requirement, and the
     * decoder that produces it is [MediaAudio]. Throws when the model in effect has no audio
     * encoder - callers are expected to have asked [canEmbedAudio] first.
     */
    suspend fun embedAudio(bytes: ByteArray): FloatArray = forwardLock.withLock {
            onWorker {
                val model = ensureLoadedLocked() ?: error("no embedding model is installed")
                require(model.audio) { "the embedding model in effect has no audio encoder" }
                if (model.isCloud) error("a cloud embedding model cannot embed audio")
                val vector = litert.embedAudio(bytes)
                require(vector.size == model.dim) {
                    "the model returned ${vector.size} values, expected ${model.dim}"
                }
                vector
            }
        }

    /**
     * Embeds one *query* with the model's query-side task prefix applied.
     *
     * A separate door from [embedDocuments] on purpose. Most embedding models are asymmetric, and
     * several of the curated entries are: searching a query embedded the way the documents were is
     * a mistake that returns plausible results rather than an error, so the two cases are told
     * apart by the type system rather than by a comment.
     */
    suspend fun embedQuery(text: String): FloatArray = forwardLock.withLock {
        onWorker {
            val model = ensureLoadedLocked() ?: error("no embedding model is installed")
            // The query side had no length guard: documents are chunked to the model's window, but a
            // pasted stack trace or a long search string went in whole. Engines that reject an
            // over-long input throw instead of truncating, so one long message killed semantic tool
            // ranking for the turn (the failure is caught to null) and made memory_search /
            // library_search fail outright.
            val budget = ChunkSpec.maxCharsFor(model.contextTokens)
                .minus(model.queryPrefix.length)
                .coerceAtLeast(1)
            val clipped = if (text.length > budget) text.take(budget) else text
            if (model.isCloud) embedCloud(model, listOf(clipped)).single()
            else embedWith(model, model.queryPrefix + clipped)
        }
    }

    private suspend fun embedWith(model: ActiveModel, text: String): FloatArray {
        val vector = if (model.litert) litert.embedNormalized(text) else embedder.embedNormalized(text)
        require(vector.size == model.dim) {
            "the model returned ${vector.size} values, expected ${model.dim}"
        }
        return vector
    }

    /**
     * Embeds through the provider, in batches, in order.
     *
     * No prefixes: this service's two doors exist because most *local* embedding models are
     * asymmetric and need to be told which side of the pair they are on. A provider's embedding
     * endpoint takes the text as it is - sending it an instruction it does not expect would be a
     * silent quality problem rather than an error.
     *
     * The order of the answer is the one thing a batching loop can get wrong, and it is the one
     * thing nothing downstream could detect: [CloudEmbeddingCaller] answers in the order of the
     * batch it was given, and the batches are concatenated in order.
     */
    private suspend fun embedCloud(model: ActiveModel, texts: List<String>): List<FloatArray> {
        val candidate = model.cloud ?: error("no cloud model is active")
        val caller = cloud ?: error("no cloud embedding client is wired up")
        // The batching and the order it preserves are [CloudEmbeddingRules.embedInBatches], which
        // is pure and therefore tested; what is left here is naming the model for the error and
        // handing the request to the transport.
        return CloudEmbeddingRules.embedInBatches(texts) { batch -> caller.embed(candidate, batch) }
    }

    /**
     * Frees the model. Called when the index has just been rebuilt - holding a few hundred
     * megabytes of idle weights through a phone's whole idle period is exactly the kind of thing
     * that makes a background feature feel expensive.
     */
    suspend fun release() = forwardLock.withLock { onWorker { lock.withLock { releaseHoldingLock() } } }

    /**
     * The body of [release]; the caller must already hold [lock].
     *
     * Split out because [loadLocked] replaces the resident model while it holds the lock, and
     * `Mutex` is not reentrant - calling the locking [release] from in there deadlocks instead of
     * failing, which is exactly how this was found.
     */
    private suspend fun releaseHoldingLock() {
        val current = active
        if (current != null) {
            // Nothing is held for a cloud model: what it costs is the request, not a resident copy
            // of any weights.
            if (!current.isCloud) {
                if (current.litert) litert.unload() else embedder.unload()
            }
        }
        active = null
        activeKey = null
    }
}

/**
 * Realistic load-time probe text for `.litertlm` models, handed to the engine prefixed with
 * the catalogue's document prefix. A bare space is not enough: the 740M build passes a space
 * probe under the GPU delegate yet returns NaN for genuine Chinese text at index time
 * (measured 2026-10-18, SM8750), which used to surface as three dead indexes instead of a
 * CPU fallback. This sentence is long, CJK-heavy and mixed-language on purpose.
 */
private const val LITERT_PROBE_TEXT =
    "语义索引让助手能够按意义检索记忆、文件与对话：即使关键词不同，相关内容也能被找到。" +
        "Semantic indexing lets the assistant retrieve by meaning rather than by keyword."
