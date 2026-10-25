package com.clma.app.data.vector

/**
 * Which accelerator a local embedding model runs on when the user has not said.
 *
 * The CPU. Not a preference - a measurement, and a narrow one, so it is worth stating exactly what
 * was found (Snapdragon 8 Elite, SM8750 / Adreno 830, 2026-10-11, release build, from `logcat`):
 *
 *  * the **vision** tower cannot be built on the GPU. `initialize()` fails with
 *    `Failed to modify graph with delegate` and the engine never comes up, which costs the user not
 *    a slower index but *no* index - cold memory, the file library, history search and the tool
 *    catalogue all sit behind this engine;
 *  * the **text** encoder on the GPU works fine. Same device, same day, once vision was back on the
 *    CPU: the engine opens, embeds, and rebuilds an index. So the GPU path here is not broken, and
 *    nothing should be concluded from this file about the GPU in general;
 *  * and for text it is not faster anyway: ~57 ms on the CPU against ~63 ms on the GPU, the
 *    difference being the per-call overhead the CPU does not pay.
 *
 * Put together: the one part that would have gained from the GPU is the one part that cannot use it,
 * and the part that can use it does not gain. A default that costs a graph the engine cannot build,
 * in exchange for nothing measurable, is not a default worth shipping - so "not chosen" means the
 * CPU, the switch stays for anyone who wants to try it, and
 * [com.clma.locallm.litert.LitertLmEmbedder.load] retries on the CPU if the attempt fails, so that
 * a bad turn of that switch cannot cost the feature.
 */
object EmbeddingAcceleratorRules {

    /**
     * Whether to use the GPU, given what the user chose.
     *
     * `null` is "not chosen", and the answer is no. A boolean is a decision, and is never
     * second-guessed: a user who turns it on gets the GPU attempt, and the retry in the loader is
     * what stands between that attempt failing and semantic search being gone.
     */
    fun useGpu(explicit: Boolean?): Boolean = explicit == true
}
