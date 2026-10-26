package com.clma.llamacpp

/**
 * A curated GGUF the embedding-model picker offers.
 *
 * Deliberately a different shape from [LlamaCppCatalogEntry] rather than a nullable-field
 * superset of it: an embedding model has no chat template, no sampler and no thinking mode,
 * while a chat model has no pooling rule and no output width. Sharing one class would mean
 * every consumer carrying fields that are meaningless for exactly one of the two uses.
 */
data class LlamaCppEmbeddingEntry(
    val displayName: String, // e.g. "EmbeddingGemma 2"
    val repo: String,        // HuggingFace repo path, e.g. "ggml-org/embeddinggemma-2-GGUF"
    val file: String,        // File inside the repo
    val sizeBytes: Long,
    /**
     * Nominal output width, for the picker label only. The authoritative value is read from
     * the GGUF at load time (`embedding_length_out`, surfaced as [LlamaCppEmbedInfo.dim]); this
     * one is here so the UI can say "768-dim" before anything is downloaded, and a disagreement
     * between the two means the file was re-converted upstream.
     */
    val dim: Int,
    /** Nominal pooling rule, same caveat as [dim] - the GGUF's own value wins. */
    val pooling: String,
    val minMemGb: Int,
    /**
     * Tokens the model is documented to accept, for the picker label only: `32768` renders as
     * "32K". Best-effort, and deliberately *not* read from the GGUF - EmbeddingGemma 2's metadata
     * claims 262 144 while the model's real window is 8K, so the header is not the authority here.
     *
     * It is not what bounds an embedding call either: the runtime context is
     * [LlamaCppEmbedder.DEFAULT_CONTEXT_TOKENS] and stays put across models, because this app
     * embeds chunks rather than whole documents. A long-context model is worth choosing for how it
     * treats a chunk, not for feeding it a whole file.
     *
     * Required rather than defaulted, because a curated entry always has a documented window and
     * the picker prints it - "639 MB · 1024-dim · 32K context" is how a user tells Qwen3-Embedding
     * apart from a 512-token model at a glance.
     */
    val contextTokens: Int,
    /**
     * Text prepended to *documents* (the things that get indexed) before embedding.
     *
     * Most modern embedding models are asymmetric: a stored passage and the query that should
     * find it are embedded differently, and the model card says so. Leaving the prefix out does
     * not fail - it quietly returns vectors from the untrained side of the contrastive pair, so
     * the damage is a worse ranking and no error anywhere. That is why this is a field on the
     * curated entry rather than something a caller remembers to add.
     *
     * Empty for models trained symmetrically (EmbeddingGemma 2, BGE-M3), which is most of them.
     */
    val documentPrefix: String = "",
    /**
     * Text prepended to *queries* before embedding. See [documentPrefix] for why this exists.
     *
     * Not necessarily the same as [documentPrefix], and for the models that need one at all it
     * is usually the query that carries it: BGE models want a retrieval instruction on the
     * query only, and the `e5` / `nomic` families want a short `query:`/`search_query:` marker
     * on one side and the matching marker on the other.
     */
    val queryPrefix: String = "",
    val tags: List<String> = emptyList(),
) {
    /** Pre-built download URL, the same `resolve` shape `ModelInstall` normalises and
     *  validates for the chat models. */
    fun resolveUrl(): String = "https://huggingface.co/$repo/resolve/main/$file"

    /**
     * [contextTokens] as the picker shows it: `8K`, `32K`, or the raw count when it is not a round
     * number of thousands.
     */
    val contextLabel: String
        get() = if (contextTokens > 0 && contextTokens % 1024 == 0) {
            "${contextTokens / 1024}K"
        } else {
            contextTokens.toString()
        }
}

/**
 * How much of a phone an entry is meant for - the rung of the ladder, not a quality verdict.
 *
 * A size ladder rather than a score, because size is the only property of an embedding model that
 * a user can act on: what it costs them in storage and mobile data. Two models of the same size can
 * differ in quality by more than two neighbouring rungs do, which is why [LlamaCppEmbeddingCatalog]
 * pairs each rung with a [EmbeddingFocus] and lets the device pick from the pair rather than
 * pretending one entry is "best".
 */
enum class EmbeddingTier(val rank: Int) {
    /** 15-200 MB. Runs on anything, and is what an unknown device is offered. */
    LIGHT(0),

    /** 200-320 MB. The default on a phone bought in the last few years. */
    STANDARD(1),

    /** 400-640 MB. The best retrieval there is on a phone, for a phone that can spare the room. */
    TOP(2),
}

/**
 * Which half of the corpus an entry is strongest on.
 *
 * Not a claim that the others cannot do Chinese (they can, badly or well) but which side of the
 * trade a user is being asked to make: the `bge-*-zh` files are Chinese-tuned and half the size,
 * the Gemma/Qwen files are multilingual and hold far more context.
 */
enum class EmbeddingFocus {
    /** The `bge-*-zh` family: Chinese-tuned, 512-token window. */
    CHINESE,

    /** EmbeddingGemma 2 and Qwen3-Embedding: many languages, 8K-32K window. */
    MULTILINGUAL,
}

/**
 * One cell of the recommendation grid: a size rung crossed with a corpus focus.
 *
 * The grid exists so that a phone can be answered with one model rather than a list. There is no
 * "best" embedding model, only the best one that fits - and fitting is a property of the device.
 * See [com.clma.app.data.vector.EmbeddingTierRules] for the part that measures it.
 */
data class EmbeddingSlot(
    val tier: EmbeddingTier,
    val focus: EmbeddingFocus,
    val entry: LlamaCppEmbeddingEntry,
)

object LlamaCppEmbeddingCatalog {
    /**
     * The curated embedding models.
     *
     * Entry criteria, set by what the runtime can actually load rather than by taste:
     *
     *  * the GGUF must declare an architecture the pinned llama.cpp knows - EmbeddingGemma 2
     *    says `gemma-embedding2`, which llama.cpp learned in `4fbc76dec51d` ("model: support
     *    embeddinggemma2"), so the submodule pin is a hard prerequisite for this entry;
     *  * the text weights must be a single self-contained GGUF. EmbeddingGemma 2 is natively
     *    multimodal, and its vision/audio halves ship as a separate `mmproj-*.gguf` - the ones
     *    listed here are the text-only files, which is all this app embeds today;
     *  * the repo must be public and ungated.
     *
     * Every repo, file name and byte size below was read off the live HuggingFace API on
     * 2026-10-10 (the first five) or 2026-10-11 (the two `bge-*-zh` additions). They are exact and
     * must not be edited, guessed at, or extended without re-verifying against the live API: a
     * wrong repo id or file name 404s on first download, which is worse than shipping no entry at
     * all.
     *
     * The order of this list is not a recommendation - [SLOTS] is. It only keeps every file the
     * catalogue knows about in one place, including the one rung that is not part of the grid
     * ([LlamaCppEmbeddingCatalog.entryFor] answers from it, which is what gives a curated file its
     * task prefix).
     */
    val ENTRIES: List<LlamaCppEmbeddingEntry> = listOf(
        LlamaCppEmbeddingEntry(
            displayName = "EmbeddingGemma 2",
            repo = "ggml-org/embeddinggemma-2-GGUF",
            file = "embeddinggemma-2-Q8_0.gguf",
            sizeBytes = 309_855_456L,
            dim = 768,
            pooling = "mean",
            minMemGb = 4,
            contextTokens = 8192,
            // No "recommended" tag: what is recommended is a [EmbeddingSlot], and which slot
            // depends on the phone. This entry is the standard rung's multilingual half.
            tags = listOf("multilingual"),
        ),
        LlamaCppEmbeddingEntry(
            displayName = "EmbeddingGemma 2 · Q4",
            // Community requant of the same model: 134 MB smaller, which is worth having on a
            // device that is tight on storage or RAM. Same text-only GGUF, same architecture.
            repo = "unsloth/embeddinggemma-2-GGUF",
            file = "embeddinggemma-2-UD-Q4_K_XL.gguf",
            sizeBytes = 175_673_856L,
            dim = 768,
            pooling = "mean",
            minMemGb = 4,
            contextTokens = 8192,
            tags = listOf("multilingual", "small"),
        ),
        LlamaCppEmbeddingEntry(
            displayName = "Qwen3-Embedding 0.6B",
            // The long-context, broadly multilingual option. 32k tokens of input, 100+ languages,
            // and the strongest Chinese retrieval of anything here that a phone can hold. Last-token
            // pooling, not mean: the runtime already takes the rule from the GGUF rather than
            // assuming one, which is what makes this entry possible at all.
            repo = "Qwen/Qwen3-Embedding-0.6B-GGUF",
            file = "Qwen3-Embedding-0.6B-Q8_0.gguf",
            sizeBytes = 639_150_592L,
            dim = 1024,
            pooling = "last",
            minMemGb = 4,
            contextTokens = 32768,
            // Qwen3-Embedding is asymmetric: the model card's retrieval instruction belongs on the
            // query, and the passage is embedded bare. Without it the model still works - it just
            // answers from the side of the pair it was not trained to search with.
            queryPrefix = "Instruct: Given a web search query, retrieve relevant passages that answer the query\nQuery: ",
            tags = listOf("multilingual", "long-context", "chinese"),
        ),
        LlamaCppEmbeddingEntry(
            displayName = "Qwen3-Embedding 0.6B · Q4",
            // Same model as the entry above, requantised 243 MB smaller - worth having on a device
            // tight on storage. It is a *different file*, so it is a different index identity: the
            // vectors are 1024-wide either way, but the numbers differ, and switching between the
            // two is a model swap like any other (which the index already handles by rebuilding).
            repo = "mradermacher/Qwen3-Embedding-0.6B-GGUF",
            file = "Qwen3-Embedding-0.6B.Q4_K_M.gguf",
            sizeBytes = 396_475_040L,
            dim = 1024,
            pooling = "last",
            minMemGb = 4,
            contextTokens = 32768,
            queryPrefix = "Instruct: Given a web search query, retrieve relevant passages that answer the query\nQuery: ",
            tags = listOf("multilingual", "long-context", "chinese", "small"),
        ),
        LlamaCppEmbeddingEntry(
            displayName = "bge-small-zh-v1.5",
            // The tiny one: 26 MB, Chinese-first, and the only entry that is comfortable on a
            // device that cannot spare a gigabyte. Punches far above its weight on Chinese notes.
            //
            // Its trained window is 512 tokens, which is why this entry could not ship before the
            // chunk budget became model-aware: a BERT run past its position embeddings does not
            // report anything, it aborts the process. Chunks are sized from the window now
            // (ChunkSpec.fitting), so a note indexed with this model is cut into ~256-character
            // pieces instead of ~1200.
            repo = "CompendiumLabs/bge-small-zh-v1.5-gguf",
            file = "bge-small-zh-v1.5-q8_0.gguf",
            sizeBytes = 26_472_640L,
            dim = 512,
            pooling = "cls",
            minMemGb = 2,
            contextTokens = 512,
            // BGE's Chinese models want a retrieval instruction on the query and nothing on the
            // passage. It is the upstream recommendation, not a guess.
            queryPrefix = "为这个句子生成表示以用于检索相关文章：",
            tags = listOf("chinese", "small"),
        ),
        LlamaCppEmbeddingEntry(
            displayName = "bge-base-zh-v1.5",
            // The light rung for a Chinese phone, and the reason it exists: 110 MB gets 768-dim
            // Chinese retrieval, where the multilingual entries need 176 MB for the same width and
            // Qwen3-Embedding needs 396. Twelve layers over a 512-token window - chunks are cut to
            // the window (ChunkSpec.fitting), so an indexed note becomes ~256-character pieces
            // rather than the ~1200 the 8K entries get. More rows in the index, better matches
            // inside each of them; that is the trade this rung asks the user to make.
            repo = "CompendiumLabs/bge-base-zh-v1.5-gguf",
            file = "bge-base-zh-v1.5-q8_0.gguf",
            sizeBytes = 110_001_568L,
            dim = 768,
            pooling = "cls",
            minMemGb = 2,
            contextTokens = 512,
            // Same upstream instruction the small model carries; BAAI publishes one for the family.
            queryPrefix = "为这个句子生成表示以用于检索相关文章：",
            tags = listOf("chinese"),
        ),
        LlamaCppEmbeddingEntry(
            displayName = "bge-large-zh-v1.5",
            // The standard rung for a Chinese phone: 24 layers, 1024-dim, and the strongest
            // Chinese-only retrieval of anything here that a phone can hold. Quantised to Q4 so it
            // lands under the standard rung's weight - the Q8 file is 348 MB, which is top-rung
            // money for a 512-token model.
            repo = "CompendiumLabs/bge-large-zh-v1.5-gguf",
            file = "bge-large-zh-v1.5-q4_k_m.gguf",
            sizeBytes = 202_115_392L,
            dim = 1024,
            pooling = "cls",
            minMemGb = 4,
            contextTokens = 512,
            queryPrefix = "为这个句子生成表示以用于检索相关文章：",
            tags = listOf("chinese"),
        ),
    )

    /**
     * The curated entry that installs [fileName], or null when it is not one of ours.
     *
     * The picker offers whatever GGUF is in the models directory, so a file can be curated, or
     * hand-copied, or a chat model the user pointed at by mistake. Only the curated ones carry
     * task prefixes; an unknown file gets none, which is the honest default - inventing a prefix
     * for a model we know nothing about would change what it retrieves for no stated reason.
     */
    fun entryFor(fileName: String): LlamaCppEmbeddingEntry? =
        ENTRIES.firstOrNull { it.file == fileName }

    /**
     * The grid: a size rung crossed with a corpus focus, six cells, one model each.
     *
     * Written as file names rather than entry references so that a cell whose file is not in
     * [ENTRIES] - a typo, or an entry deleted without its cell - drops out here instead of
     * throwing while the class initialises. `LlamaCppEmbeddingCatalogTest` asserts that all six
     * cells survive, so the failure lands in CI rather than on a device.
     */
    private val SLOT_FILES: List<Triple<EmbeddingTier, EmbeddingFocus, String>> = listOf(
        Triple(EmbeddingTier.LIGHT, EmbeddingFocus.CHINESE, "bge-base-zh-v1.5-q8_0.gguf"),
        Triple(EmbeddingTier.LIGHT, EmbeddingFocus.MULTILINGUAL, "embeddinggemma-2-UD-Q4_K_XL.gguf"),
        Triple(EmbeddingTier.STANDARD, EmbeddingFocus.CHINESE, "bge-large-zh-v1.5-q4_k_m.gguf"),
        Triple(EmbeddingTier.STANDARD, EmbeddingFocus.MULTILINGUAL, "embeddinggemma-2-Q8_0.gguf"),
        Triple(EmbeddingTier.TOP, EmbeddingFocus.CHINESE, "Qwen3-Embedding-0.6B.Q4_K_M.gguf"),
        Triple(EmbeddingTier.TOP, EmbeddingFocus.MULTILINGUAL, "Qwen3-Embedding-0.6B-Q8_0.gguf"),
    )

    /** The six cells, in rung order; each rung holds its Chinese entry before its multilingual one. */
    val SLOTS: List<EmbeddingSlot> = SLOT_FILES.mapNotNull { (tier, focus, file) ->
        ENTRIES.firstOrNull { it.file == file }?.let { EmbeddingSlot(tier, focus, it) }
    }

    /** The cell a tier and a focus name, or null when the grid has no such cell. */
    fun forSlot(tier: EmbeddingTier, focus: EmbeddingFocus): EmbeddingSlot? =
        SLOTS.firstOrNull { it.tier == tier && it.focus == focus }

    /**
     * Every curated model in the order a picker should offer it: the grid first, rung by rung, and
     * then anything in [ENTRIES] the grid does not place (the tiny `bge-small-zh-v1.5`) as an
     * extra. Nothing is hidden - a phone that cannot hold the standard rung can still install it by
     * hand - the order only says which one is being suggested.
     */
    val PICKER_ORDER: List<LlamaCppEmbeddingEntry> =
        SLOTS.map { it.entry } +
            ENTRIES.filter { entry -> SLOTS.none { it.entry.file == entry.file } }

    /**
     * [PICKER_ORDER] as file names, which is the shape [EmbeddingModelRules.pick] wants: the
     * fallback for an install that has never chosen a model, tried in this order against whatever
     * is actually on disk. So the rung the user was offered comes first, and a device that already
     * holds a bigger model still keeps using it.
     */
    val curatedOrder: List<String> = PICKER_ORDER.map { it.file }
}
