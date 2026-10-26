package com.clma.app.data.vector

import com.clma.llamacpp.LlamaCppEmbeddingCatalog
import com.clma.locallm.litert.LitertEmbeddingCatalog
import com.clma.app.data.datastore.Settings

/**
 * "Can anything embed right now, and what?" - answered once, for every screen that asks.
 *
 * Four places ask it (the settings row, the assistant page's warning, the first-run notice, and
 * the library/memory coordinators through the service) and they have to agree: a screen that says
 * semantic search is set up while the service refuses to embed is worse than either answer alone.
 * So the question is resolved here, from the same [EmbeddingModelRules] and [CloudEmbeddingRules]
 * the service uses, and the screens only render the answer.
 *
 * The cloud choice is the branch that decides first: if the user turned the cloud backend on and
 * picked a model this build can call, that is what runs. A cloud selection that *cannot* be used -
 * a deleted model, or a provider whose protocol this build does not speak - falls back to the
 * local model rather than blocking the feature, and [cloudModel] returns null so the screens can
 * say which one is actually in effect.
 */
object EmbeddingReadiness {

    /**
     * The cloud model that will run, or null when the local one will.
     *
     * Null covers three different situations on purpose - the backend is local, no model is chosen,
     * or the chosen one cannot be called - because all three have the same consequence here, and
     * the screen that needs to tell them apart has the settings in hand to do it.
     */
    fun cloudModel(settings: Settings): CloudEmbeddingCandidate? {
        if (!CloudEmbeddingRules.isCloud(settings.embeddingBackend)) return null
        return CloudEmbeddingRules
            .pick(
                configuredModelUuid = settings.embeddingCloudModel,
                candidates = CloudEmbeddingModels.candidates(settings.providers),
            )
            ?.takeIf { it.supported }
    }

    /** The local model file that resolves, or null when none does. */
    fun localModelFile(settings: Settings, installedLocalFiles: List<String>): String? =
        EmbeddingModelRules.pick(
            configured = settings.embeddingModelFile.takeIf { it.isNotBlank() },
            installed = installedLocalFiles,
            curatedOrder = EmbeddingCatalog.curatedOrder,
        )

    /** Whether a vector can be produced at all. */
    fun isReady(settings: Settings, installedLocalFiles: List<String>): Boolean =
        cloudModel(settings) != null || localModelFile(settings, installedLocalFiles) != null

    /**
     * Whether the embedding model in effect reads pictures itself.
     *
     * The switch that turns native image embedding on only *means* something when this is true, so
     * the screen that offers it is disabled otherwise rather than hiding a control whose effect
     * would be invisible. False for a cloud backend whatever the local files are: a provider's
     * embedding endpoint takes text, so an image there still has to become words first.
     *
     * Pure, and here rather than in the screen, because it is the same question the coordinator
     * asks before choosing a path - and the two must not be able to disagree.
     */
    fun localModelReadsImages(settings: Settings, installedLocalFiles: List<String>): Boolean {
        if (cloudModel(settings) != null) return false
        val file = localModelFile(settings, installedLocalFiles) ?: return false
        return LitertEmbeddingCatalog.entryFor(file)?.vision == true
    }

    /**
     * What to show as the model in use: the cloud model's name, or whatever the user configured
     * for the local one - including the empty string, which the screens read as "picked
     * automatically".
     */
    fun label(settings: Settings): String =
        cloudModel(settings)?.displayName ?: settings.embeddingModelFile
}
