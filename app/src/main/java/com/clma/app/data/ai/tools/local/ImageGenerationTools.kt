package me.rerere.rikkahub.data.ai.tools.local

import android.graphics.BitmapFactory
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
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.ImageAspectRatio
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.GenMediaRepository
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * P2-33 — the `generate_image` / `edit_image` local tools.
 *
 * Until now image generation was only reachable from its own page (`ImgGenPage`); the model had
 * no way to ask for a picture. These two tools close that gap: they take a prompt (and, for
 * edits, source file paths), run the app's configured image model — the same
 * `Settings.imageGenerationModelId` the page uses — save the result into the gallery exactly
 * like the page does, and return a `UIMessagePart.Image` so the picture renders inline in the
 * chat (the same channel `show_image` already proved out) plus a JSON envelope for the model.
 *
 * The pure decisions live in [ImageGenerationLogic]; this file is only the device-touching shell.
 */

private const val TAG = "ImageGenerationTools"

internal const val TOOL_GENERATE_IMAGE = "generate_image"
internal const val TOOL_EDIT_IMAGE = "edit_image"

/**
 * `generate_image` — text-to-image.
 *
 * No approval is attached: same risk profile as `take_screenshot` / `show_image` / `camera_photo`
 * (the model can already ask for a screenshot and read arbitrary files), and the user chose the
 * auto-approved tier for this card.
 */
fun generateImageTool(
    settingsStore: SettingsStore,
    providerManager: ProviderManager,
    filesManager: FilesManager,
    genMediaRepository: GenMediaRepository,
    modelCanSeeImages: Boolean,
): Tool = Tool(
    name = TOOL_GENERATE_IMAGE,
    description = "Generate an image from a text prompt using the app's configured image model. " +
        "The result is saved to the gallery and shown inline in the chat. Use this whenever the " +
        "user asks for a picture, illustration, logo, poster or any visual. Describe the subject, " +
        "style, lighting and composition in `prompt`; it is passed verbatim to the image model.",
    parameters = { imageToolSchema(requireImages = false) },
    execute = { input ->
        val args = input.jsonObject
        val prompt = args["prompt"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (prompt.isEmpty()) {
            return@Tool listOf(UIMessagePart.Text(buildImageGenErrorEnvelope("missing_prompt", "`prompt` is required.")))
        }
        val aspectRatio = parseImageAspectRatio(args["aspect_ratio"]?.jsonPrimitive?.contentOrNull)
            ?: return@Tool listOf(UIMessagePart.Text(invalidAspectRatioEnvelope(args)))
        runImageTool(
            toolName = TOOL_GENERATE_IMAGE,
            prompt = prompt,
            aspectRatio = aspectRatio,
            count = clampImageCount(args["count"]?.jsonPrimitive?.intOrNull),
            requestedModel = args["model"]?.jsonPrimitive?.contentOrNull,
            sourcePaths = null,
            settingsStore = settingsStore,
            providerManager = providerManager,
            filesManager = filesManager,
            genMediaRepository = genMediaRepository,
            modelCanSeeImages = modelCanSeeImages,
        )
    },
)

/**
 * `edit_image` — prompt-guided edit of one or more existing local image files (recolour, add or
 * remove an element, restyle, ...). Same output contract as [generateImageTool].
 */
fun editImageTool(
    settingsStore: SettingsStore,
    providerManager: ProviderManager,
    filesManager: FilesManager,
    genMediaRepository: GenMediaRepository,
    modelCanSeeImages: Boolean,
): Tool = Tool(
    name = TOOL_EDIT_IMAGE,
    description = "Edit or remix existing image files with a text prompt (e.g. \"make it night\", " +
        "\"add a red hat\", \"turn it into a watercolour\"). `images` are local file paths; the " +
        "edited result is saved to the gallery and shown inline in the chat.",
    parameters = { imageToolSchema(requireImages = true) },
    execute = { input ->
        val args = input.jsonObject
        val prompt = args["prompt"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (prompt.isEmpty()) {
            return@Tool listOf(UIMessagePart.Text(buildImageGenErrorEnvelope("missing_prompt", "`prompt` is required.")))
        }
        val rawPaths = (args["images"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        if (rawPaths.isEmpty()) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildImageGenErrorEnvelope("missing_images", "`images` must list at least one local image path.")
                )
            )
        }
        val sourcePaths = ArrayList<String>(rawPaths.size)
        for (raw in rawPaths) {
            val path = AgentWorkspace.expand(raw.removePrefix("file://"))
            PathSafetyGuard.check(path)?.let { violation ->
                return@Tool listOf(UIMessagePart.Text(fmErrEnvelope(violation.code, violation.detail)))
            }
            if (!File(path).isFile) {
                return@Tool listOf(
                    UIMessagePart.Text(buildImageGenErrorEnvelope("image_not_found", "Source image not found: $raw"))
                )
            }
            sourcePaths.add(path)
        }
        val aspectRatio = parseImageAspectRatio(args["aspect_ratio"]?.jsonPrimitive?.contentOrNull)
            ?: return@Tool listOf(UIMessagePart.Text(invalidAspectRatioEnvelope(args)))
        runImageTool(
            toolName = TOOL_EDIT_IMAGE,
            prompt = prompt,
            aspectRatio = aspectRatio,
            count = clampImageCount(args["count"]?.jsonPrimitive?.intOrNull),
            requestedModel = args["model"]?.jsonPrimitive?.contentOrNull,
            sourcePaths = sourcePaths,
            settingsStore = settingsStore,
            providerManager = providerManager,
            filesManager = filesManager,
            genMediaRepository = genMediaRepository,
            modelCanSeeImages = modelCanSeeImages,
        )
    },
)

private fun invalidAspectRatioEnvelope(args: JsonObject): String =
    buildImageGenErrorEnvelope(
        "invalid_aspect_ratio",
        "Unsupported aspect_ratio '${args["aspect_ratio"]?.jsonPrimitive?.contentOrNull?.trim()}'. " +
            "Use one of: square, landscape, portrait.",
    )

/** The shared parameter schema; `edit_image` adds the required `images` array. */
private fun imageToolSchema(requireImages: Boolean): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("prompt", buildJsonObject {
            put("type", "string")
            put(
                "description",
                if (requireImages) {
                    "How the source image(s) should change. Be specific about what to add, " +
                        "remove, recolour or restyle."
                } else {
                    "What to draw. Be specific about subject, style, lighting and composition."
                },
            )
        })
        if (requireImages) {
            put("images", buildJsonObject {
                put("type", "array")
                put("items", buildJsonObject { put("type", "string") })
                put("description", "Local file paths of the source image(s) to edit.")
            })
        }
        put("aspect_ratio", buildJsonObject {
            put("type", "string")
            put("description", "One of square / landscape / portrait. Defaults to square.")
        })
        put("count", buildJsonObject {
            put("type", "integer")
            put("description", "How many images to produce, 1-$MAX_IMAGE_GEN_COUNT. Defaults to 1.")
        })
        put("model", buildJsonObject {
            put("type", "string")
            put(
                "description",
                "Optional image model id or display name; defaults to the app's configured " +
                    "image-generation model.",
            )
        })
    },
    required = if (requireImages) listOf("prompt", "images") else listOf("prompt"),
)

/**
 * Shared body of both tools: resolve the model, call the provider, persist every final image and
 * build the result parts. Errors are turned into envelopes, never thrown — a tool that throws
 * surfaces as an opaque `tool_failed`, which tells the model nothing actionable.
 */
private suspend fun runImageTool(
    toolName: String,
    prompt: String,
    aspectRatio: ImageAspectRatio,
    count: Int,
    requestedModel: String?,
    sourcePaths: List<String>?,
    settingsStore: SettingsStore,
    providerManager: ProviderManager,
    filesManager: FilesManager,
    genMediaRepository: GenMediaRepository,
    modelCanSeeImages: Boolean,
): List<UIMessagePart> {
    val settings = settingsStore.settingsFlow.first()
    val models = settings.providers.flatMap { it.models }
    val index = selectImageModelIndex(
        models = models.map {
            ImageModelChoice(id = it.id.toString(), modelId = it.modelId, displayName = it.displayName)
        },
        configuredId = settings.imageGenerationModelId.toString(),
        requested = requestedModel,
    )
    if (index < 0) {
        return listOf(
            UIMessagePart.Text(
                buildImageGenErrorEnvelope(
                    "no_image_model",
                    "No usable image model. Choose one in Settings -> Image generation, or pass `model`.",
                )
            )
        )
    }
    val model = models[index]
    val provider = model.findProvider(settings.providers)
        ?: return listOf(
            UIMessagePart.Text(
                buildImageGenErrorEnvelope(
                    "provider_not_found",
                    "Provider for model '${model.displayName}' is not configured.",
                )
            )
        )

    val providerImpl = providerManager.getProviderByType(provider)
    val items: Flow<ImageGenerationItem> = if (sourcePaths == null) {
        providerImpl.generateImage(
            providerSetting = provider,
            params = ImageGenerationParams(
                model = model,
                prompt = prompt,
                numOfImages = count,
                aspectRatio = aspectRatio,
                customHeaders = model.customHeaders,
                customBody = model.customBodies,
            ),
        )
    } else {
        providerImpl.editImage(
            providerSetting = provider,
            params = ImageEditParams(
                model = model,
                prompt = prompt,
                images = sourcePaths,
                numOfImages = count,
                aspectRatio = aspectRatio,
                customHeaders = model.customHeaders,
                customBody = model.customBodies,
            ),
        )
    }

    val saved = try {
        saveGeneratedImages(
            items = items,
            modelName = model.displayName,
            prompt = prompt,
            type = if (sourcePaths == null) GenMediaEntity.TYPE_IMAGE_GENERATION else GenMediaEntity.TYPE_IMAGE_EDIT,
            sourcePaths = sourcePaths?.joinToString("\n"),
            filesManager = filesManager,
            genMediaRepository = genMediaRepository,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "$toolName failed", e)
        return listOf(
            UIMessagePart.Text(
                buildImageGenErrorEnvelope("image_generation_failed", e.message ?: "The image model call failed.")
            )
        )
    }

    if (saved.isEmpty()) {
        return listOf(
            UIMessagePart.Text(buildImageGenErrorEnvelope("no_images_returned", "The image model returned no images."))
        )
    }

    return buildList {
        saved.forEach { info -> add(UIMessagePart.Image(url = "file://${info.path}")) }
        add(UIMessagePart.Text(buildImageGenEnvelope(toolName, prompt, model.displayName, saved, modelCanSeeImages)))
    }
}

/**
 * Persists every **final** image (streaming previews are for the page's live view, not a chat
 * bubble) into the same directory and gallery table the image-generation page writes to, so the
 * assistant's output shows up in the in-app gallery too.
 */
private suspend fun saveGeneratedImages(
    items: Flow<ImageGenerationItem>,
    modelName: String,
    prompt: String,
    type: String,
    sourcePaths: String?,
    filesManager: FilesManager,
    genMediaRepository: GenMediaRepository,
): List<GeneratedImageInfo> {
    val saved = mutableListOf<GeneratedImageInfo>()
    var index = 0
    items.collect { item ->
        if (item.partial) return@collect
        val timestamp = System.currentTimeMillis()
        val filename = imageGenFilename(modelName, timestamp, index)
        val file = File(filesManager.getImagesDir(), filename)
        filesManager.createImageFileFromBase64(item.data, file.absolutePath)
        genMediaRepository.insertMedia(
            GenMediaEntity(
                path = imageGalleryRelativePath(filename),
                modelId = modelName,
                prompt = prompt,
                createAt = timestamp,
                type = type,
                sourcePaths = sourcePaths,
            )
        )
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        saved += GeneratedImageInfo(
            path = file.absolutePath,
            width = bounds.outWidth,
            height = bounds.outHeight,
            sizeBytes = file.length(),
        )
        index++
    }
    return saved
}
