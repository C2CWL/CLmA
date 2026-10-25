package com.clma.app.data.vector

import com.clma.llamacpp.LlamaCppEmbeddingCatalog
import com.clma.locallm.litert.LitertEmbeddingCatalog

/**
 * The curated embedding files, in the order they win.
 *
 * One list rather than two, because [EmbeddingModelRules.pick] answers "which installed file do we
 * embed with" by walking *one* order, and the two runtimes' catalogues would each only know half
 * the answer. Both the service and every readiness check read this, so they cannot disagree about
 * which model is in effect - a screen that says semantic search is set up while the service refuses
 * to embed is worse than either answer alone.
 *
 * ## Why LiteRT-LM is first
 *
 * It is the backend this app standardized on: one engine that embeds text and images into the same
 * space, with the prefixes and the window declared in one place. A GGUF is still perfectly usable
 * (and its catalogue is still here, so an existing install keeps working without re-downloading
 * 400 MB), but when both are present the LiteRT one is what a fresh index should be built with.
 *
 * ## The order
 *
 * The `.litertlm` entries first, then the GGUF grid in rung order. The LiteRT one is the only
 * model that can embed a picture as a picture, so when it is installed it is the one to use - and
 * when it is not, the GGUF side answers in the order the picker offers, which is the ladder.
 *
 * The order only decides ties. A user's explicit choice always wins, and an uninstalled file is
 * never picked - see `EmbeddingModelRules.pick`.
 */
object EmbeddingCatalog {

    val curatedOrder: List<String> = buildList {
        addAll(LitertEmbeddingCatalog.curatedOrder)
        addAll(LlamaCppEmbeddingCatalog.curatedOrder)
    }
}
