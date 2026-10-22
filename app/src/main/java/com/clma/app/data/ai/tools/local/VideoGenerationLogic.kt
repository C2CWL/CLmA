package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.ImageAspectRatio

/**
 * P2-33b — the pure core of the `generate_video` local tool, a deliberate mirror of
 * [ImageGenerationLogic]. The tool body ([VideoGenerationTools]) reaches into DataStore, Room and
 * the filesystem, so everything decidable without a device lives here: argument parsing, model
 * selection, output naming and the result envelope the model reads. Keeping the split means a
 * bare-JVM harness can pin the decisions while CI covers the wiring.
 */

/** The tool's accepted aspect-ratio tokens, mapped onto the shared [ImageAspectRatio]. */
internal fun parseVideoAspectRatio(raw: String?): ImageAspectRatio? =
    when (raw?.trim()?.lowercase().orEmpty()) {
        // Absent/blank → landscape: the natural shape for a clip, and the provider default.
        "" -> ImageAspectRatio.LANDSCAPE
        "landscape", "16:9", "wide", "horizontal" -> ImageAspectRatio.LANDSCAPE
        "portrait", "9:16", "tall", "vertical" -> ImageAspectRatio.PORTRAIT
        "square", "1:1" -> ImageAspectRatio.SQUARE
        else -> null
    }

/** Schema bounds for `duration_seconds`; vendors clamp further (see the provider request builders). */
internal const val MIN_VIDEO_DURATION_SECONDS: Int = 2
internal const val MAX_VIDEO_DURATION_SECONDS: Int = 15

/**
 * Upper bound on `count`. Deliberately smaller than the image tool's 4-in-one-call: neither video
 * vendor takes an `n`, so N clips are N sequential jobs, each of which can take minutes.
 */
internal const val MAX_VIDEO_GEN_COUNT: Int = 4

/**
 * Clamps a requested clip length into the tool's advertised range; `null` stays `null` (let the
 * model/vendor pick its own default).
 */
internal fun clampVideoDuration(raw: Int?): Int? =
    raw?.coerceIn(MIN_VIDEO_DURATION_SECONDS, MAX_VIDEO_DURATION_SECONDS)

/** Clamps a requested clip count into `1..`[MAX_VIDEO_GEN_COUNT]; absent → 1. */
internal fun clampVideoCount(raw: Int?): Int = (raw ?: 1).coerceIn(1, MAX_VIDEO_GEN_COUNT)

/**
 * The slice of a provider `Model` the selection logic needs, flattened so this file stays free of
 * the provider graph. [id] is the model UUID — what `Settings.videoGenerationModelId` stores;
 * [modelId] is the provider-side id a user would recognise.
 */
internal data class VideoModelChoice(
    val id: String,
    val modelId: String,
    val displayName: String,
)

/**
 * Picks the video model to generate with, returning its index in [models], or `-1` when nothing
 * resolves. Precedence mirrors [selectImageModelIndex]:
 *  1. [requested] — an explicit tool argument, matched case-insensitively against model id, then
 *     display name;
 *  2. [configuredId] — the app's configured video-generation model, matched by UUID.
 *
 * An explicit request that matches nothing falls through to the configured model; the success
 * envelope reports the model actually used, so the substitution is visible.
 */
internal fun selectVideoModelIndex(
    models: List<VideoModelChoice>,
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

private val VIDEO_FILENAME_UNSAFE_CHARS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

/**
 * Makes a model display name safe as a **single** filename segment. A copy of the gallery's own
 * sanitizer — see [sanitizeImageFilenameComponent] for why the duplication is deliberate.
 */
internal fun sanitizeVideoFilenameComponent(name: String): String =
    name.map { c -> if (c in VIDEO_FILENAME_UNSAFE_CHARS || c.isISOControl()) '_' else c }.joinToString("")

/** `<ts>_<model>_<index>.mp4` — the gallery's filename shape, video edition. */
internal fun videoGenFilename(modelName: String, timestamp: Long, index: Int): String =
    "${timestamp}_${sanitizeVideoFilenameComponent(modelName)}_$index.mp4"

/** The relative path stored in `gen_media.path`, mirroring the gallery's `videos/<name>`. */
internal fun videoGalleryRelativePath(filename: String): String = "videos/$filename"

/** Metadata for one saved clip — the shape the tool reports back to the model. */
internal data class GeneratedVideoInfo(
    val path: String,
    val sizeBytes: Long,
    val mimeType: String,
)

/**
 * The success envelope `generate_video` hands the model.
 *
 * Mirrors [buildImageGenEnvelope]: the `UIMessagePart.Video` is the *user's* display, so a model
 * that cannot ingest video is told plainly it cannot watch the clip rather than being handed a
 * byte count that reads like "I watched it" (and then confabulating a description).
 */
internal fun buildVideoGenEnvelope(
    tool: String,
    prompt: String,
    model: String,
    videos: List<GeneratedVideoInfo>,
    modelCanSeeVideos: Boolean,
    firstFrame: String? = null,
): String = buildJsonObject {
    put("success", true)
    put("tool", tool)
    put("model", model)
    put("prompt", prompt)
    put("mode", if (firstFrame == null) "text_to_video" else "image_to_video")
    firstFrame?.let { put("first_frame", it) }
    put("count", videos.size)
    put("videos", buildJsonArray {
        videos.forEach { video ->
            add(
                buildJsonObject {
                    put("path", video.path)
                    put("size_bytes", video.sizeBytes)
                    put("mime_type", video.mimeType)
                }
            )
        }
    })
    if (!modelCanSeeVideos) {
        put("visible_to_you", false)
        put(
            "note",
            "The video is now displayed to the user, but the current model has no video input " +
                "capability — you cannot watch it. Do not guess or describe what the clip shows; " +
                "the file is at the reported path if you need to hand it to another tool.",
        )
    }
}.toString()

/** The structured refusal the tool emits — the same `error`/`detail` shape the app uses elsewhere. */
internal fun buildVideoGenErrorEnvelope(code: String, detail: String): String = buildJsonObject {
    put("error", code)
    put("detail", detail)
}.toString()
