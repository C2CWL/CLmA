package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.ImageAspectRatio

/**
 * P2-33 — the pure core of the `generate_image` / `edit_image` local tools.
 *
 * The tool bodies ([ImageGenerationTools]) reach into Android (`BitmapFactory`), DataStore and
 * Room, so everything that can be answered without a device lives here instead: argument
 * parsing, model selection, output naming and the result envelope the model reads. Keeping
 * the split means a bare-JVM kotlinc harness can pin the decisions while CI covers the wiring.
 */

/** Upper bound on `count` for both tools — mirrors `ImgGenVM.updateNumberOfImages`. */
internal const val MAX_IMAGE_GEN_COUNT: Int = 4

/**
 * Parses the `aspect_ratio` tool argument.
 *
 * Returns [ImageAspectRatio.SQUARE] for an absent/blank argument (the generator's own default),
 * the matching ratio for a recognised token, and **`null`** for a non-blank token we do not
 * understand — the caller answers that with a structured error instead of silently generating
 * a shape the model did not ask for.
 */
internal fun parseImageAspectRatio(raw: String?): ImageAspectRatio? = when (raw?.trim()?.lowercase().orEmpty()) {
    "" -> ImageAspectRatio.SQUARE
    "square", "1:1" -> ImageAspectRatio.SQUARE
    "landscape", "16:9", "wide" -> ImageAspectRatio.LANDSCAPE
    "portrait", "9:16", "tall" -> ImageAspectRatio.PORTRAIT
    else -> null
}

/** Clamps a requested image count into `1..`[MAX_IMAGE_GEN_COUNT]; absent → 1. */
internal fun clampImageCount(raw: Int?): Int = (raw ?: 1).coerceIn(1, MAX_IMAGE_GEN_COUNT)

/**
 * The slice of a provider `Model` the selection logic needs, flattened so this file stays free
 * of the provider graph. [id] is the model's UUID — the value `Settings.imageGenerationModelId`
 * actually stores; [modelId] is the provider-side id the user would recognise.
 */
internal data class ImageModelChoice(
    val id: String,
    val modelId: String,
    val displayName: String,
)

/**
 * Picks the model to generate with, returning its index in [models], or `-1` when nothing
 * resolves.
 *
 * Precedence:
 *  1. [requested] — an explicit tool argument, matched case-insensitively against model id and
 *     then display name;
 *  2. [configuredId] — the app's configured image-generation model, matched by UUID.
 *
 * An explicit request that matches nothing **falls through** to the configured model rather than
 * failing the call; the success envelope always reports the model actually used, so the model can
 * see the substitution. Only when neither resolves is `-1` returned.
 */
internal fun selectImageModelIndex(
    models: List<ImageModelChoice>,
    configuredId: String?,
    requested: String?,
): Int {
    val wanted = requested?.trim()?.takeIf { it.isNotEmpty() }
    if (wanted != null) {
        models.indexOfFirst { it.modelId.equals(wanted, ignoreCase = true) }
            .takeIf { it >= 0 }
            ?.let { return it }
        models.indexOfFirst { it.displayName.equals(wanted, ignoreCase = true) }
            .takeIf { it >= 0 }
            ?.let { return it }
    }
    val configured = configuredId?.trim()?.takeIf { it.isNotEmpty() } ?: return -1
    return models.indexOfFirst { it.id == configured }
}

private val IMAGE_FILENAME_UNSAFE_CHARS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

/**
 * Makes a model display name safe as a **single** filename segment.
 *
 * Deliberately a copy of the gallery's `sanitizeFilenameComponent`
 * (`ui/pages/imggen/ImgGenVM.kt`, #39): that one lives in a ViewModel file this layer must not
 * depend on, and it is three lines. Keeping the tools' output filenames byte-identical to the
 * gallery's matters more than deduplicating a sanitizer.
 */
internal fun sanitizeImageFilenameComponent(name: String): String =
    name.map { c -> if (c in IMAGE_FILENAME_UNSAFE_CHARS || c.isISOControl()) '_' else c }.joinToString("")

/** The gallery's own filename shape: `<ts>_<model>_<index>.png`. */
internal fun imageGenFilename(modelName: String, timestamp: Long, index: Int): String =
    "${timestamp}_${sanitizeImageFilenameComponent(modelName)}_$index.png"

/** The relative path stored in `gen_media.path`, mirroring `ImgGenVM.saveImageToStorage`. */
internal fun imageGalleryRelativePath(filename: String): String = "images/$filename"

/** Metadata for one saved image — the shape both tools report back to the model. */
internal data class GeneratedImageInfo(
    val path: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
)

/**
 * The success envelope `generate_image` / `edit_image` hand the model.
 *
 * Mirrors `show_image`: the `UIMessagePart.Image` is the *user's* display, so a text-only model
 * is told plainly it cannot see the picture rather than being handed dimensions that read like
 * "I looked at it" (and then confabulating a description).
 */
internal fun buildImageGenEnvelope(
    tool: String,
    prompt: String,
    model: String,
    images: List<GeneratedImageInfo>,
    modelCanSeeImages: Boolean,
): String = buildJsonObject {
    put("success", true)
    put("tool", tool)
    put("model", model)
    put("prompt", prompt)
    put("count", images.size)
    put("images", buildJsonArray {
        images.forEach { image ->
            add(
                buildJsonObject {
                    put("path", image.path)
                    put("width", image.width)
                    put("height", image.height)
                    put("size_bytes", image.sizeBytes)
                }
            )
        }
    })
    if (!modelCanSeeImages) {
        put("visible_to_you", false)
        put(
            "note",
            "The image(s) are now displayed to the user, but the current model has no vision " +
                "capability — you cannot see the result. Do not guess or describe what the " +
                "picture shows; if you need its contents, read the file at the reported path.",
        )
    }
}.toString()

/** The structured refusal both tools emit — the same `error`/`detail` shape the app uses elsewhere. */
internal fun buildImageGenErrorEnvelope(code: String, detail: String): String = buildJsonObject {
    put("error", code)
    put("detail", detail)
}.toString()
