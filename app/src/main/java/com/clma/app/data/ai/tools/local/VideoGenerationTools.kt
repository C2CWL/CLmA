package me.rerere.rikkahub.data.ai.tools.local

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.VideoGenerationParams
import me.rerere.ai.ui.ImageAspectRatio
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.VideoGenerationItem
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.GenMediaRepository
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * P2-33b — the `generate_video` local tool, the video sibling of `generate_image` / `edit_image`.
 *
 * Image generation had its own page but no way for the model to ask for a picture; that gap was
 * closed by [generateImageTool]. Video has the same shape of gap, so this closes it the same way:
 * run the app's configured video model — the same `Settings.videoGenerationModelId` the (upcoming)
 * video page will use — save the result into the gallery the way the page will, and return a
 * `UIMessagePart.Video` so the clip renders inline in the chat, plus a JSON envelope for the model.
 *
 * Unlike images there is no separate `edit_image` counterpart — image-to-video is a **mode** of this
 * one tool: pass `images` (a first frame) to animate a picture instead of generating from text
 * alone. `count` runs N sequential jobs, since neither vendor takes an `n` for video.
 *
 * The pure decisions live in [VideoGenerationLogic]; this file is only the device-touching shell.
 */

private const val TAG = "VideoGenerationTools"

internal const val TOOL_GENERATE_VIDEO = "generate_video"

/**
 * `generate_video` — text-to-video.
 *
 * Approval tier mirrors `generate_image` (auto-approved): the model can already spend the user's
 * money on an image call, and the assistant opted this card in. Promote it in Settings ->
 * Tool approvals if a paid, multi-minute call should always be confirmed first.
 */
fun generateVideoTool(
    settingsStore: SettingsStore,
    providerManager: ProviderManager,
    filesManager: FilesManager,
    genMediaRepository: GenMediaRepository,
    modelCanSeeVideos: Boolean,
): Tool = Tool(
    name = TOOL_GENERATE_VIDEO,
    description = "Generate a short video from a text prompt using the app's configured video " +
        "model (DashScope Wan, Volcengine Seedance, MiniMax Hailuo, Zhipu CogVideoX or " +
        "SiliconFlow). Takes one to several minutes; the result " +
        "is saved to the gallery and shown inline in the chat. Use this when the user asks for a " +
        "video or animation. Describe subject, action, camera movement, style and lighting in " +
        "`prompt`. To animate an existing image, pass its local path in `images` " +
        "(image-to-video).",
    parameters = { videoToolSchema() },
    execute = { input ->
        val args = input.jsonObject
        val prompt = args["prompt"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (prompt.isEmpty()) {
            return@Tool listOf(
                UIMessagePart.Text(buildVideoGenErrorEnvelope("missing_prompt", "`prompt` is required."))
            )
        }
        val aspectRatio = parseVideoAspectRatio(args["aspect_ratio"]?.jsonPrimitive?.contentOrNull)
            ?: return@Tool listOf(UIMessagePart.Text(invalidVideoAspectRatioEnvelope(args)))

        // Optional first frame(s) for image-to-video. Same expansion + safety path `edit_image`
        // uses, since these are user-supplied local files.
        val rawPaths = (args["images"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        val sourcePaths = ArrayList<String>(rawPaths.size)
        for (raw in rawPaths) {
            val path = AgentWorkspace.expand(raw.removePrefix("file://"))
            PathSafetyGuard.check(path)?.let { violation ->
                return@Tool listOf(UIMessagePart.Text(fmErrEnvelope(violation.code, violation.detail)))
            }
            if (!File(path).isFile) {
                return@Tool listOf(
                    UIMessagePart.Text(
                        buildVideoGenErrorEnvelope("image_not_found", "First-frame image not found: $raw")
                    )
                )
            }
            sourcePaths.add(path)
        }

        runVideoTool(
            prompt = prompt,
            aspectRatio = aspectRatio,
            durationSeconds = clampVideoDuration(args["duration_seconds"]?.jsonPrimitive?.intOrNull),
            count = clampVideoCount(args["count"]?.jsonPrimitive?.intOrNull),
            sourcePaths = sourcePaths,
            requestedModel = args["model"]?.jsonPrimitive?.contentOrNull,
            settingsStore = settingsStore,
            providerManager = providerManager,
            filesManager = filesManager,
            genMediaRepository = genMediaRepository,
            modelCanSeeVideos = modelCanSeeVideos,
        )
    },
)

private fun invalidVideoAspectRatioEnvelope(args: JsonObject): String =
    buildVideoGenErrorEnvelope(
        "invalid_aspect_ratio",
        "Unsupported aspect_ratio " +
            "'${args["aspect_ratio"]?.jsonPrimitive?.contentOrNull?.trim()}'. " +
            "Use one of: landscape, portrait, square.",
    )

/** The `generate_video` parameter schema. */
private fun videoToolSchema(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("prompt", buildJsonObject {
            put("type", "string")
            put(
                "description",
                "What the video should show. Describe subject, action, camera movement, style and " +
                    "lighting. Passed verbatim to the video model.",
            )
        })
        put("aspect_ratio", buildJsonObject {
            put("type", "string")
            put("description", "One of landscape / portrait / square. Defaults to landscape.")
        })
        put("duration_seconds", buildJsonObject {
            put("type", "integer")
            put(
                "description",
                "Requested clip length in seconds, $MIN_VIDEO_DURATION_SECONDS" +
                    "-$MAX_VIDEO_DURATION_SECONDS. Omit to let the model choose (usually 5). Some " +
                    "models are fixed at 5 s and ignore this.",
            )
        })
        put("images", buildJsonObject {
            put("type", "array")
            put("items", buildJsonObject { put("type", "string") })
            put(
                "description",
                "Optional local image path(s) to animate (image-to-video). Only the first is used " +
                    "as the first frame. Omit for text-to-video.",
            )
        })
        put("count", buildJsonObject {
            put("type", "integer")
            put(
                "description",
                "How many clips to produce, 1-$MAX_VIDEO_GEN_COUNT. Defaults to 1. Each clip is a " +
                    "separate, multi-minute job.",
            )
        })
        put("model", buildJsonObject {
            put("type", "string")
            put(
                "description",
                "Optional video model id or display name; defaults to the app's configured " +
                    "video-generation model.",
            )
        })
    },
    required = listOf("prompt"),
)

/**
 * The tool body: resolve the model, call the provider, persist the clip and build the result parts.
 * Errors become envelopes, never throws — a thrown tool surfaces as an opaque `tool_failed` that
 * tells the model nothing actionable.
 */
private suspend fun runVideoTool(
    prompt: String,
    aspectRatio: ImageAspectRatio,
    durationSeconds: Int?,
    count: Int,
    sourcePaths: List<String>,
    requestedModel: String?,
    settingsStore: SettingsStore,
    providerManager: ProviderManager,
    filesManager: FilesManager,
    genMediaRepository: GenMediaRepository,
    modelCanSeeVideos: Boolean,
): List<UIMessagePart> {
    val settings = settingsStore.settingsFlow.first()
    val models = settings.providers.flatMap { it.models }
    val index = selectVideoModelIndex(
        models = models.map {
            VideoModelChoice(id = it.id.toString(), modelId = it.modelId, displayName = it.displayName)
        },
        configuredId = settings.videoGenerationModelId.toString(),
        requested = requestedModel,
    )
    if (index < 0) {
        return listOf(
            UIMessagePart.Text(
                buildVideoGenErrorEnvelope(
                    "no_video_model",
                    "No usable video model. Configure one (DashScope Wan, Volcengine Seedance, " +
                        "MiniMax Hailuo, Zhipu CogVideoX or SiliconFlow) " +
                        "and select it, or pass `model`.",
                )
            )
        )
    }
    val model = models[index]
    val provider = model.findProvider(settings.providers)
        ?: return listOf(
            UIMessagePart.Text(
                buildVideoGenErrorEnvelope(
                    "provider_not_found",
                    "Provider for model '${model.displayName}' is not configured.",
                )
            )
        )

    val providerImpl = providerManager.getProviderByType(provider)
    val items: Flow<VideoGenerationItem> = providerImpl.generateVideo(
        providerSetting = provider,
        params = VideoGenerationParams(
            model = model,
            prompt = prompt,
            aspectRatio = aspectRatio,
            durationSeconds = durationSeconds,
            numOfVideos = count,
            sourceImages = sourcePaths,
            customHeaders = model.customHeaders,
            customBody = model.customBodies,
        ),
    )

    val saved = try {
        saveGeneratedVideos(
            items = items,
            modelName = model.displayName,
            prompt = prompt,
            filesManager = filesManager,
            genMediaRepository = genMediaRepository,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "generate_video failed", e)
        return listOf(
            UIMessagePart.Text(
                buildVideoGenErrorEnvelope(
                    "video_generation_failed",
                    e.message ?: "The video model call failed.",
                )
            )
        )
    }

    if (saved.isEmpty()) {
        return listOf(
            UIMessagePart.Text(
                buildVideoGenErrorEnvelope("no_videos_returned", "The video model returned no videos.")
            )
        )
    }

    return buildList {
        saved.forEach { info -> add(UIMessagePart.Video(url = "file://${info.path}")) }
        add(
            UIMessagePart.Text(
                buildVideoGenEnvelope(
                    TOOL_GENERATE_VIDEO,
                    prompt,
                    model.displayName,
                    saved,
                    modelCanSeeVideos,
                    firstFrame = sourcePaths.firstOrNull(),
                )
            )
        )
    }
}

/**
 * Persists every final clip into the same directory and gallery table the video page will write to,
 * so the assistant's output shows up in the in-app gallery too.
 */
private suspend fun saveGeneratedVideos(
    items: Flow<VideoGenerationItem>,
    modelName: String,
    prompt: String,
    filesManager: FilesManager,
    genMediaRepository: GenMediaRepository,
): List<GeneratedVideoInfo> {
    val saved = mutableListOf<GeneratedVideoInfo>()
    var index = 0
    items.collect { item ->
        if (item.partial) return@collect
        val timestamp = System.currentTimeMillis()
        val filename = videoGenFilename(modelName, timestamp, index)
        val file = File(filesManager.getVideosDir(), filename)
        filesManager.createVideoFileFromBase64(item.data, file.absolutePath)
        genMediaRepository.insertMedia(
            GenMediaEntity(
                path = videoGalleryRelativePath(filename),
                modelId = modelName,
                prompt = prompt,
                createAt = timestamp,
                type = GenMediaEntity.TYPE_VIDEO_GENERATION,
            )
        )
        saved += GeneratedVideoInfo(
            path = file.absolutePath,
            sizeBytes = file.length(),
            mimeType = item.mimeType,
        )
        index++
    }
    return saved
}
