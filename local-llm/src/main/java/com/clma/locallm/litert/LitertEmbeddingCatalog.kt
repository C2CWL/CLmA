package com.clma.locallm.litert

/**
 * A curated `.litertlm` embedding model, in the same spirit as `LlamaCppEmbeddingCatalog`.
 *
 * Deliberately its own type rather than a field added to [LiteRtCatalogEntry]: that one carries a
 * chat model's config defaults (sampler, thinking mode, tool-calling), none of which mean anything
 * for an embedding model, and an embedding model has a pooling side, prefixes and an input
 * signature that a chat model has no use for.
 *
 * ## Why the file name matters more here than anywhere else
 *
 * The `litert-community` repos publish *one file per SoC* next to a plain file -- e.g.
 * `embeddinggemma-2-text-vision-440m.litertlm` (CPU/GPU) and
 * `embeddinggemma-2-text-vision-440m_Qualcomm_SM8750.litertlm` (an AOT build for that NPU). The
 * per-SoC files are **not** a faster variant of the same thing: measured on a Snapdragon 8 Elite
 * (2026-10-11), the `_Qualcomm_SM8750` file fails on this app's engine every way round -- CPU and
 * NPU both die with `Failed to allocate tensors`, GPU with `Failed to modify graph with delegate`
 * -- while the plain file embeds fine on all three. So the entry below names the plain file, and
 * picking a per-SoC file by hand is a way to get an engine that will not start.
 *
 * ## The signature is the window
 *
 * A `.litertlm` ships several input signatures (text: 128 / 256 / 512 / 1024 / 2048 / 8192) and
 * `maxInputLength` picks which one to load. It is not a hint: the loaded signature is both the
 * arena size and the longest text the engine will accept -- it *refuses* a longer text rather than
 * truncating. That is why [contextTokens] reports the signature and not the model's headline
 * window: everything that sizes chunks reads it, and a chunk sized by 8192 would fail on a 512
 * signature.
 */
data class LitertEmbeddingEntry(
    val displayName: String,
    /** HuggingFace repo path, e.g. `litert-community/embeddinggemma-2-text-vision-440m-litert-lm`. */
    val repo: String,
    /** File inside the repo. Must be the plain (no SoC suffix) file. */
    val file: String,
    val sizeBytes: Long,
    /** Output width. Nominal, for the picker label; the engine's own answer wins at load time. */
    val dim: Int,
    /** Tokens the model is documented to accept at most, for the label only. */
    val maxContextTokens: Int,
    /** Which input signature to load. Must be one the file actually ships. */
    val signature: Int,
    /**
     * Whether this model embeds images as images.
     *
     * True only for the text-vision files. It is a property of the *file*, and everything that
     * indexes an image reads it to decide between the two ways of handling one: a vision model
     * embeds the picture itself, while a text-only model can only be fed what someone else read out
     * of it (on-device OCR, or a description written by a vision model). The second is a fallback
     * with a ceiling - it is whatever the recogniser or the describer happened to say - so the
     * difference is worth carrying in the catalogue rather than discovering at run time.
     */
    val vision: Boolean,
    /**
     * Whether this model embeds sound as sound.
     *
     * True only for the files that carry the 300M audio encoder. Same role as [vision]: it is a
     * property of the *file*, and everything that indexes audio reads it to decide whether the
     * bytes can go straight to the engine. There is no text fallback for sound - nothing in this
     * app transcribes it - so a false here means audio files are simply not indexed rather than
     * indexed worse.
     */
    val audio: Boolean = false,
    /** Text prepended to *documents*. See `LlamaCppEmbeddingEntry` for why this is not optional. */
    val documentPrefix: String,
    /** Text prepended to *queries*. */
    val queryPrefix: String,
    val minMemGb: Int,
) {
    /** The load-time window: what [signature] actually accepts. */
    val contextTokens: Int get() = signature

    /** Pre-built download URL on HuggingFace's `resolve` path, the shape `ModelInstall` validates. */
    fun resolveUrl(): String = "https://huggingface.co/$repo/resolve/main/$file"
}

object LitertEmbeddingCatalog {

    /**
     * The prefixes are the ones the model card prints (`google/embeddinggemma-2`, "Task Instruction
     * Prefixes"): search is an asymmetric task, so the query side and the document side differ.
     * Omitting a prefix still runs -- it just quietly returns vectors from the untrained side of
     * the contrastive pair, which shows up as a worse ranking and never as an error.
     *
     * Prefixes apply to **text only**; images, video and audio are passed with no prefix at all.
     */
    private const val SEARCH_QUERY_PREFIX = "task: search result | query: "
    private const val DOCUMENT_PREFIX = "title: none | text: "

    val ENTRIES: List<LitertEmbeddingEntry> = listOf(
        LitertEmbeddingEntry(
            displayName = "EmbeddingGemma 2 440M (text + vision)",
            repo = "litert-community/embeddinggemma-2-text-vision-440m-litert-lm",
            file = "embeddinggemma-2-text-vision-440m.litertlm",
            sizeBytes = 387_710_976L,
            dim = 768,
            maxContextTokens = 8_192,
            // 512 rather than 128: the engine refuses anything longer than the loaded signature, and
            // 128 tokens is under a paragraph -- the chunker would have to cut notes into pieces
            // too small to retrieve. 512 is the smallest signature that still holds a real chunk,
            // and the arena stays modest.
            signature = 512,
            // The 440M is the text+vision variant: its whole point is that a screenshot and a
            // sentence about it land in the same place, with no OCR and no description in between.
            vision = true,
            documentPrefix = DOCUMENT_PREFIX,
            queryPrefix = SEARCH_QUERY_PREFIX,
            minMemGb = 4,
        ),
        LitertEmbeddingEntry(
            displayName = "EmbeddingGemma 2 740M (text + vision + audio)",
            repo = "litert-community/embeddinggemma-2-740m-litert-lm",
            // The plain file, for the same reason as above: the per-SoC `_Qualcomm_SM8750` build
            // is an NPU-only artefact and does not allocate its tensors on a CPU/GPU configuration.
            // Note: on the GPU delegate this build passes a space probe but returns NaN for real
            // CJK text (2026-10-18, SM8750); the realistic load-time probe in EmbeddingService
            // is what keeps it on the CPU. Do not slim that probe back down.
            file = "embeddinggemma-2-740m.litertlm",
            // Read off the HuggingFace API on 2026-10-17; see the note on ENTRIES about exactness.
            sizeBytes = 484_622_336L,
            dim = 768,
            maxContextTokens = 8_192,
            // 2048 rather than the 512 of the 440M entry, and the reason is audio: the model
            // charges 25 tokens per second of sound, so 2048 tokens is what makes a segment of
            // roughly 80 seconds fit. A 512 signature would cap a segment at 20 seconds, which is
            // shorter than a single sentence of a podcast. The arena grows with this, which is the
            // one risk the caller sees as a failed open rather than as wrong answers.
            signature = 2_048,
            vision = true,
            audio = true,
            documentPrefix = DOCUMENT_PREFIX,
            queryPrefix = SEARCH_QUERY_PREFIX,
            // 485 MB of weights plus the 300M audio encoder resident at once.
            minMemGb = 6,
        ),
    )

    /** Which input signatures the file ships. The override is validated against this list. */
    val SIGNATURES: List<Int> = listOf(128, 256, 512, 1024, 2048, 8192)

    fun entryFor(fileName: String): LitertEmbeddingEntry? =
        ENTRIES.firstOrNull { it.file == fileName.substringAfterLast('/') }

    /** File names in the order the picker offers them, for `EmbeddingModelRules.pick`. */
    val curatedOrder: List<String> get() = ENTRIES.map { it.file }
}
