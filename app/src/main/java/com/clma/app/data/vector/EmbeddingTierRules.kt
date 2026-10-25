package com.clma.app.data.vector

import com.clma.llamacpp.EmbeddingFocus
import com.clma.llamacpp.EmbeddingSlot
import com.clma.llamacpp.EmbeddingTier
import com.clma.llamacpp.LlamaCppEmbeddingCatalog
import com.clma.llamacpp.LlamaCppEmbeddingEntry

/**
 * What a phone can spare, as far as choosing an embedding model is concerned.
 *
 * Three numbers and a flag, deliberately plain: the rule that reads them is a pure function, so it
 * can be tested for a 3 GB phone and a flagship without either being in the room. The reader that
 * fills this in from the platform is [EmbeddingDeviceProbe].
 */
data class EmbeddingDeviceFacts(
    /** Total physical RAM, from `ActivityManager.MemoryInfo.totalMem`. 0 when it cannot be read. */
    val totalRamBytes: Long,

    /** Free space on the volume the models are downloaded to, in bytes. */
    val availableDiskBytes: Long,

    /**
     * Whether this phone is set up in Chinese, which is the only honest guess available at this
     * point: the content is not readable until after a model has been installed, and asking the
     * user to describe their documents before they can search them is a worse first run than
     * being wrong once. It picks a lane, not a fate - both lanes stay installable, and a wrong
     * guess costs one lane switch.
     */
    val prefersChinese: Boolean,
)

/**
 * Which of the curated embedding models this phone should be offered.
 *
 * There is no best embedding model, only a best one that fits: a 640 MB file is a mistake on a
 * 3 GB phone and a waste to withhold from a flagship. The catalogue states the grid
 * (`LlamaCppEmbeddingCatalog.SLOTS`) and this states the measurement, so that the picker, the
 * first-run notice and any future "what should I download" hint all answer the same way instead
 * of each inventing a preference.
 *
 * Pure by construction - no `Context`, no clock, no I/O - because the interesting cases (a phone
 * with 8 GB of RAM and 400 MB free; one set to English with a Chinese model already installed) are
 * exactly the ones nobody owns.
 */
object EmbeddingTierRules {

    /**
     * The rung an unknown device is offered, and the ceiling for one that reports nothing.
     *
     * Not the middle rung: a first run should cost the user as little as possible, and the light
     * rung is a real model rather than a placeholder - 110 MB of Chinese retrieval or 176 MB of
     * multilingual. A phone that can afford more says so through its own numbers.
     */
    val DEFAULT_TIER: EmbeddingTier = EmbeddingTier.LIGHT

    /**
     * How much free space beyond the file itself a rung is worth installing into.
     *
     * The file is not the whole cost: the vector index for a directory of notes is built alongside
     * it, and a download that exactly fills the disk leaves the app unable to write that index -
     * a failure that arrives after the wait rather than before it. Three times the model is the
     * blunt version of "the file, plus the index, plus room to keep using the phone".
     */
    const val DISK_HEADROOM = 3

    /**
     * RAM at or above which the standard rung is offered, and at or above which the top rung is.
     *
     * Product policy rather than physics. The models themselves are mmapped, so a 176 MB file
     * loads on a phone with 2 GB as readily as on one with 12 - what these numbers are about is
     * everything *else* the phone is running while the embedding does: an assistant, a browser and
     * a camera are all resident, and a model chosen to the last megabyte is a model that makes the
     * phone feel slow. `LlamaCppEmbeddingEntry.minMemGb` states the narrower question ("will this
     * load at all") and is deliberately not consulted here; for the embedding entries it is
     * documentation.
     */
    const val STANDARD_RAM_BYTES = 6L * 1024 * 1024 * 1024
    const val TOP_RAM_BYTES = 8L * 1024 * 1024 * 1024

    /**
     * Whether this entry can live on this device.
     *
     * Disk only: RAM decides which *rung* is offered ([highestFittingTier]), and this decides
     * whether the file on that rung is worth starting a download for.
     */
    fun fits(entry: LlamaCppEmbeddingEntry, facts: EmbeddingDeviceFacts): Boolean =
        fits(entry.sizeBytes, facts)

    /**
     * The same question for a file rather than an entry.
     *
     * The `.litertlm` catalogue has its own entry type - different fields, same cost - and the
     * picker has to be able to say "not enough room" for both without knowing which runtime a row
     * came from.
     */
    fun fits(sizeBytes: Long, facts: EmbeddingDeviceFacts): Boolean =
        facts.availableDiskBytes >= sizeBytes * DISK_HEADROOM

    /** The lane this phone is offered, from the only hint available before anything is indexed. */
    fun focusOf(facts: EmbeddingDeviceFacts): EmbeddingFocus =
        if (facts.prefersChinese) EmbeddingFocus.CHINESE else EmbeddingFocus.MULTILINGUAL

    /**
     * The highest rung this phone is offered *and* can take.
     *
     * Rungs are checked from the top down and the first acceptable one wins, so the answer is
     * monotone: a device that gets the top rung could also have taken the ones below it.
     */
    fun highestFittingTier(facts: EmbeddingDeviceFacts): EmbeddingTier {
        val lane = focusOf(facts)
        val top = LlamaCppEmbeddingCatalog.forSlot(EmbeddingTier.TOP, lane)
        val standard = LlamaCppEmbeddingCatalog.forSlot(EmbeddingTier.STANDARD, lane)
        return when {
            top != null && facts.totalRamBytes >= TOP_RAM_BYTES && fits(top.entry, facts) ->
                EmbeddingTier.TOP

            standard != null &&
                facts.totalRamBytes >= STANDARD_RAM_BYTES &&
                fits(standard.entry, facts) -> EmbeddingTier.STANDARD

            else -> DEFAULT_TIER
        }
    }

    /**
     * The one model to suggest, or null when the grid holds nothing this phone can take.
     *
     * Null is a real answer and not a failure: a phone with 300 MB free should be told to free up
     * space, not handed a 176 MB download that will fill the disk it is trying to index.
     *
     * There is no cross-lane fallback, deliberately. The tempting one - "this phone cannot take
     * its own lane, suggest the other" - is wrong in the only direction it could ever run: a
     * Chinese-tuned model does not answer an English corpus at all, while the multilingual entries
     * answer Chinese imperfectly but usefully, so the swap is never an improvement. It is also
     * unreachable: within every rung the Chinese entry is the smaller of the two, so a device that
     * failed the multilingual entry would have failed the Chinese one first.
     */
    fun recommendedSlot(facts: EmbeddingDeviceFacts): EmbeddingSlot? =
        LlamaCppEmbeddingCatalog
            .forSlot(highestFittingTier(facts), focusOf(facts))
            ?.takeIf { fits(it.entry, facts) }
}
