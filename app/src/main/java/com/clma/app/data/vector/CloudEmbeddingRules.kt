package com.clma.app.data.vector

/**
 * One model the user has marked as an embedding model, described without dragging the provider
 * graph along.
 *
 * A projection rather than a `Model`: the rules below are about identity and arithmetic, and both
 * of them are things an index can be silently wrong about. Keeping them off the provider types is
 * what lets them be tested without one.
 */
data class CloudEmbeddingCandidate(
    /** `ProviderSetting.id`, the stable half of this model's identity. */
    val providerId: String,
    /** The provider's display name, for the sentence that explains why it cannot be used. */
    val providerName: String,
    /** `Model.id` - what the user's choice is stored as. */
    val modelUuid: String,
    /** `Model.modelId`, the name the provider's API knows it by. */
    val modelId: String,
    val displayName: String,
    /** Whether this build can call an embeddings endpoint on this provider at all. */
    val supported: Boolean,
    /** The model's window, when its metadata says; null means "assume the default". */
    val contextLength: Int?,
)

/**
 * Which cloud model is in use, and what it leaves behind in the index.
 *
 * Pure, and separate from the transport for the same reason [EmbeddingModelRules] is separate from
 * the embedder: the decision here - which model, called how, charged how many tokens - is one that
 * fails by producing an index that looks fine, and it deserves a test rather than a line in a
 * settings screen.
 *
 * The one thing this file cannot decide is whether the *user* wants their notes and files sent to
 * a provider at all. That is a setting, it is off by default, and the screen that turns it on says
 * so in as many words.
 */
object CloudEmbeddingRules {

    /** The local GGUF, as before. **The default**, and the value an untouched settings file has. */
    const val BACKEND_LOCAL = "local"

    /** A provider's embeddings endpoint. Every request carries the indexed text off the device. */
    const val BACKEND_CLOUD = "cloud"

    /** Marks the `model_id` of every row embedded by a cloud model. */
    const val INDEX_MODEL_ID_PREFIX = "cloud:"

    /**
     * How many inputs one request may carry.
     *
     * The API accepts far more, but a batch is a request body built from a whole document's
     * chunks: on a phone, over a metered connection, thirty-two is already a large POST, and the
     * indexes that use this feed it one document at a time anyway.
     */
    const val EMBED_BATCH = 32

    /**
     * The window to size inputs by when the model's metadata does not say.
     *
     * Only a floor in practice: [ChunkSpec.fitting] takes the smaller of this and its own
     * character cap, and the cap is already what the local models are limited to, so a cloud
     * model with more room is not made to use it - it is only spared the truncation a *small*
     * local window would impose here and there is no reason to.
     */
    const val DEFAULT_CONTEXT_TOKENS = 8_192

    /** Whether the cloud backend is the one in use. Anything but [BACKEND_CLOUD] means local. */
    fun isCloud(backend: String): Boolean = backend == BACKEND_CLOUD

    /**
     * The chosen model, or null when nothing usable is chosen.
     *
     * [configuredModelUuid] wins outright when it still exists - a choice the user made is never
     * second-guessed, and a model that was deleted is "nothing", not "some other model", because
     * the alternative is embedding a whole index with a model nobody picked.
     *
     * The `supported` flag is *not* filtered here: a chosen model this build cannot call has to
     * stay visible long enough to be explained, and a picker that silently shows nothing selected
     * is how a user ends up re-picking a model that was never the problem.
     */
    fun pick(
        configuredModelUuid: String?,
        candidates: List<CloudEmbeddingCandidate>,
    ): CloudEmbeddingCandidate? {
        val wanted = configuredModelUuid?.trim().orEmpty()
        if (wanted.isEmpty()) return null
        return candidates.firstOrNull { it.modelUuid == wanted }
    }

    /**
     * The identity stored on every row of the index, in the shape `vector_chunks.model_id` wants.
     *
     * Carries the provider as well as the model name, because two providers can serve the same
     * name and "the index was built by this model" has to mean *this* model.
     *
     * The name is normalized to hold no `/`. That is not cosmetic: the identity reaches the
     * database through [EmbeddingModelRules.modelIdOf], which strips everything before the last
     * slash - a rule written for file paths - and providers do name models with one
     * (`openai/text-embedding-3-small` on OpenRouter, `BAAI/bge-m3` on several). Left alone, two
     * different models would quietly share an id and the index would never notice.
     */
    fun indexModelId(candidate: CloudEmbeddingCandidate): String =
        "$INDEX_MODEL_ID_PREFIX${candidate.providerId}:${candidate.modelId.replace('/', '_')}"

    /** The window to size inputs by: the model's own, or [DEFAULT_CONTEXT_TOKENS] when unknown. */
    fun contextTokens(candidate: CloudEmbeddingCandidate): Int =
        candidate.contextLength?.takeIf { it > 0 } ?: DEFAULT_CONTEXT_TOKENS

    /**
     * Sends [texts] through [embed] in [EMBED_BATCH]-sized requests, and hands the vectors back in
     * the order the texts were given.
     *
     * The batching lives here, rather than in the service that calls it, for the sake of the one
     * thing it can get wrong and nothing downstream could notice: order. A document's chunks are
     * embedded together and stored in the order they came back, so a loop that answered the second
     * batch before the first - or that let a caller's `map` interleave with the answers - would
     * file every passage under the wrong vector, and search would return plausible nonsense for as
     * long as the index lived. [embed] must answer one vector per input, in that order; that is
     * checked rather than trusted, because a provider that silently drops an input is a real thing
     * and the alternative is a misaligned index.
     */
    suspend fun embedInBatches(
        texts: List<String>,
        embed: suspend (List<String>) -> List<FloatArray>,
    ): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val vectors = texts.chunked(EMBED_BATCH).flatMap { batch ->
            val answer = embed(batch)
            require(answer.size == batch.size) {
                "asked for ${batch.size} vectors and got ${answer.size}"
            }
            answer
        }
        val width = vectors.first().size
        require(vectors.all { it.size == width }) {
            "the same request answered vectors of differing widths"
        }
        return vectors
    }
}
