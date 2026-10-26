package com.clma.llamacpp

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.clma.locallm.MemoryGuard
import org.json.JSONObject

/**
 * What the native engine makes of the GGUF it was handed. Read once, at load time, and
 * reported to the caller so nothing downstream has to hardcode a model's shape: the
 * embedding width and the pooling rule come from the model's own metadata, and the two run
 * paths ([hasEncoder]) are what llama.cpp insists on being told apart.
 */
data class LlamaCppEmbedInfo(
    /** Width of one embedding vector, i.e. the dimension a stored index must match. */
    val dim: Int,
    /** llama.cpp's pooling rule for this GGUF: `mean`, `last`, `cls`, `none`, ... */
    val pooling: String,
    val hasEncoder: Boolean,
    val hasDecoder: Boolean,
    val nCtxTrain: Int,
)

/**
 * Seam over [LlamaCppJni]'s embedding entry points, so [LlamaCppEmbedder]'s state machine is
 * testable on the JVM without native code.
 *
 * Deliberately separate from [LlamaCppNative] (the chat seam) rather than an extension of it:
 * the two share nothing past loading a file, and a combined interface would force every chat
 * test stub to grow four embedding members it never calls.
 */
interface LlamaCppEmbedNative {
    fun loadModel(path: String): Long
    fun freeModel(handle: Long)
    fun modelInfo(handle: Long): String
    fun createEmbedContext(modelHandle: Long, nCtx: Int, nThreads: Int): Long
    fun freeEmbedContext(handle: Long)
    fun embedModelInfo(handle: Long): String
    fun embed(handle: Long, text: String): FloatArray
}

/** Delegates straight to [LlamaCppJni]. */
object RealLlamaCppEmbedNative : LlamaCppEmbedNative {
    override fun loadModel(path: String) = LlamaCppJni.nativeLoadModel(path)
    override fun freeModel(handle: Long) = LlamaCppJni.nativeFreeModel(handle)
    override fun modelInfo(handle: Long): String = LlamaCppJni.nativeModelInfo(handle)
    override fun createEmbedContext(modelHandle: Long, nCtx: Int, nThreads: Int) =
        LlamaCppJni.nativeCreateEmbedContext(modelHandle, nCtx, nThreads)

    override fun freeEmbedContext(handle: Long) = LlamaCppJni.nativeFreeEmbedContext(handle)
    override fun embedModelInfo(handle: Long): String = LlamaCppJni.nativeEmbedModelInfo(handle)
    override fun embed(handle: Long, text: String): FloatArray = LlamaCppJni.embed(handle, text)
}

/**
 * Owns one loaded embedding model and its context.
 *
 * The lifecycle mirrors [LlamaCppRuntime] - same [MemoryGuard] pre-check, same reason for the
 * [Mutex] - with two differences that follow from what embeddings are for:
 *
 *  * [embed] is a suspend call that takes the lock for one text and returns, rather than
 *    holding it for a token stream. An index build embeds hundreds of chunks; serialising
 *    them is required (llama_context is not thread-safe) but there is no cancellation flag to
 *    expose, because each call is short enough that finishing it is cheaper than interrupting.
 *  * there is no prompt, template or sampler anywhere - an embedding model has one input and
 *    one output, and anything shaped like a chat would be a lie about what is happening.
 */
class LlamaCppEmbedder(private val native: LlamaCppEmbedNative = RealLlamaCppEmbedNative) {

    @Volatile
    private var modelHandle: Long = 0

    @Volatile
    private var engineHandle: Long = 0

    @Volatile
    private var facts: LlamaCppEmbedInfo? = null

    private val mutex = Mutex()

    /** Shape of the loaded model, or null when nothing is loaded. */
    val info: LlamaCppEmbedInfo? get() = facts

    val isLoaded: Boolean get() = engineHandle != 0L

    /**
     * Loads [path] and prepares an embedding context of [nCtx] tokens. Any previously loaded
     * model is released first.
     *
     * [availableRamBytes] is the same pre-check the chat runtime makes, for the same reason:
     * being killed by the OS partway through a load looks like a crash, while a refusal is a
     * decision the caller can report. Pass [Long.MAX_VALUE] to skip the check.
     */
    suspend fun load(
        path: String,
        availableRamBytes: Long = Long.MAX_VALUE,
        nCtx: Int = DEFAULT_CONTEXT_TOKENS,
        threads: Int = defaultThreads(),
    ): LlamaCppEmbedInfo = mutex.withLock {
        unloadLocked()

        val model = native.loadModel(path)

        // Refuse before building the context. An over-budget load is killed by the OS partway
        // through, which reads as a crash rather than as a decision. A weights size of 0 is not
        // a small model but unreadable metadata: MemoryGuard.decide(0, ...) always answers Ok,
        // so it has to be refused here or an unmeasurable model sails past the very guard meant
        // to judge it.
        val engine = try {
            val weightsBytes = JSONObject(native.modelInfo(model)).optLong("weights_bytes", 0L)
            if (weightsBytes <= 0) {
                throw ModelTooLargeException(
                    "This model's file size could not be read, so it cannot be safely loaded."
                )
            }
            when (val decision = MemoryGuard.decide(weightsBytes, availableRamBytes)) {
                is MemoryGuard.Decision.TooLarge -> throw ModelTooLargeException(
                    "This model needs about ${decision.requiredFreeBytes / 1_000_000}MB free " +
                        "but only ${decision.availMemBytes / 1_000_000}MB is available. " +
                        "Close other apps or pick a smaller model."
                )
                MemoryGuard.Decision.Ok -> Unit
            }
            native.createEmbedContext(model, nCtx, threads)
        } catch (t: Throwable) {
            native.freeModel(model)
            throw t
        }

        // Reading the facts is kept out of the block above so that its failure path can free
        // the context as well: the context is native memory nobody else owns, and dropping it
        // on the floor would keep the whole model resident for the life of the process.
        val info = try {
            decodeEmbedInfo(native.embedModelInfo(engine))
        } catch (t: Throwable) {
            native.freeEmbedContext(engine)
            native.freeModel(model)
            throw t
        }

        modelHandle = model
        engineHandle = engine
        facts = info
        info
    }

    /** Embeds one text, returning its raw pooled vector (see [embedNormalized]). */
    suspend fun embed(text: String): FloatArray = mutex.withLock {
        val engine = engineHandle
        check(engine != 0L) { "no embedding model is loaded" }
        native.embed(engine, text)
    }

    /**
     * [embed] followed by [VectorMath.l2Normalize]. This is the form an index wants: with unit
     * vectors a cosine similarity is a plain dot product, and a stored index does not have to
     * remember a per-vector magnitude.
     */
    suspend fun embedNormalized(text: String): FloatArray = VectorMath.l2Normalize(embed(text))

    /** Releases the context and the model, in that order - the context must not outlive it. */
    suspend fun unload() = mutex.withLock { unloadLocked() }

    private fun unloadLocked() {
        if (engineHandle != 0L) {
            native.freeEmbedContext(engineHandle)
            engineHandle = 0
        }
        if (modelHandle != 0L) {
            native.freeModel(modelHandle)
            modelHandle = 0
        }
        facts = null
    }

    companion object {
        /**
         * Tokens one embedding call may consume. A chunk of a document is what gets embedded
         * here, never a whole file, so this is headroom over the chunk size rather than a
         * statement about the model's own context length - which the GGUF reports as
         * `n_ctx_train` and which can be far larger (EmbeddingGemma 2 declares 262 144).
         *
         * It is a *request*, not a guarantee: the native engine clamps the context it creates to
         * the model's trained window, so a model that only knows 512 tokens gets 512. The window
         * that actually applies is reported back as [LlamaCppEmbedInfo.nCtxTrain], and inputs must
         * be sized by that.
         */
        const val DEFAULT_CONTEXT_TOKENS: Int = 2048

        private fun defaultThreads(): Int =
            (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 8)

        private fun decodeEmbedInfo(json: String): LlamaCppEmbedInfo {
            val o = JSONObject(json)
            return LlamaCppEmbedInfo(
                dim = o.getInt("dim"),
                pooling = o.getString("pooling"),
                hasEncoder = o.getBoolean("has_encoder"),
                hasDecoder = o.getBoolean("has_decoder"),
                nCtxTrain = o.optInt("n_ctx_train", 0),
            )
        }
    }
}
