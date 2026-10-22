package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.ImageAspectRatio
import me.rerere.ai.util.json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * DashScope (Aliyun Bailian) image generation, on the provider's **native** HTTP API.
 *
 * Why this file exists: DashScope is configured as a plain OpenAI-compatible provider
 * (`https://dashscope.aliyuncs.com/compatible-mode/v1`), and that base URL has **no**
 * `/images/generations` endpoint — it answers `404`, while `/chat/completions` on the same
 * host answers `401` (i.e. the route exists). So the generic OpenAI image path can never work
 * there, and DashScope keeps two native shapes instead:
 *
 *  - **Legacy** (`qwen-image`, `qwen-image-plus`, `wanx*`, `wan2.*`): asynchronous task API —
 *    `POST text2image/image-synthesis` (with `X-DashScope-Async: enable`) returns a `task_id`,
 *    then `GET /api/v1/tasks/{id}` is polled until `SUCCEEDED`.
 *  - **New generation** (`qwen-image-2.x` / `qwen-image-3.x`): the **synchronous** multimodal
 *    endpoint `POST multimodal-generation/generation`, whose body carries the prompt inside
 *    `input.messages` and whose response already contains the image URLs.
 *
 * Calling a model on the wrong one of these answers `400 InvalidParameter` with the
 * (misleadingly worded) message `url error, please check url！` — see
 * [isDashScopeEndpointModelMismatch].
 *
 * Editing (`qwen-image-edit*`, the `qwen-image-2.x` / `3.x` line, `wan2.6-image`) rides the **same**
 * two shapes with the source image inlined into the request: the modern family edits on
 * [DASHSCOPE_MULTIMODAL_GENERATION_PATH] by adding `{image}` parts next to the `{text}` one, while
 * the legacy `wanx*imageedit` family uses its own async task endpoint,
 * [DASHSCOPE_IMAGE_EDIT_SYNTHESIS_PATH], with `input.function` / `input.base_image_url`. See
 * [buildDashScopeMultimodalEditRequestBody] and [buildDashScopeEditRequestBody].
 *
 * Everything that can be decided without a socket lives here (root derivation, size mapping,
 * request bodies, response parsing) so a bare-JVM unit test can pin it, mirroring the split
 * used for the OpenRouter Images API in [OpenRouterRequestBuilder].
 */

/**
 * DashScope is reachable on two hosts — `dashscope.aliyuncs.com` (China) and
 * `dashscope-intl.aliyuncs.com` (International) — so matching a literal host string, or a
 * suffix like `dashscope.aliyuncs.com`, misses the `-intl` one. Match the first label instead,
 * which also rejects a typo-squat such as `notdashscope.aliyuncs.com`.
 */
private const val ALIYUNS_HOST_SUFFIX = ".aliyuncs.com"

/** The host of [baseUrl] when it is a DashScope host, else `null`. */
private fun dashScopeHost(baseUrl: String): String? {
    val host = baseUrl.toHttpUrlOrNull()?.host?.lowercase() ?: return null
    if (!host.endsWith(ALIYUNS_HOST_SUFFIX)) return null
    val firstLabel = host.substringBefore('.')
    return if (firstLabel == "dashscope" || firstLabel.startsWith("dashscope-")) host else null
}

/** `POST {root}/api/v1/services/aigc/text2image/image-synthesis` (with `X-DashScope-Async: enable`). */
const val DASHSCOPE_IMAGE_SYNTHESIS_PATH = "/api/v1/services/aigc/text2image/image-synthesis"

/** Poll attempts before giving up (≈ 2 minutes at the interval below). */
const val DASHSCOPE_POLL_ATTEMPTS = 24

/** Delay between two `GET /api/v1/tasks/{id}` polls. */
const val DASHSCOPE_POLL_INTERVAL_MS = 5_000L

/** True when [baseUrl] points at a DashScope host (China or International). */
fun isDashScopeBaseUrl(baseUrl: String): Boolean = dashScopeHost(baseUrl) != null

/**
 * The DashScope *native* API root for an OpenAI-compatible [baseUrl] — scheme + host (+ non-default
 * port), with any `/compatible-mode/v1` path dropped:
 *
 * ```
 * https://dashscope.aliyuncs.com/compatible-mode/v1   ->   https://dashscope.aliyuncs.com
 * ```
 *
 * Returns `null` when [baseUrl] is not a DashScope host, so the caller can fall through to the
 * generic path instead of building a nonsense URL.
 */
fun dashScopeNativeRoot(baseUrl: String): String? {
    if (dashScopeHost(baseUrl) == null) return null
    val url = baseUrl.toHttpUrlOrNull() ?: return null
    return url.newBuilder()
        .encodedPath("/")
        .query(null)
        .fragment(null)
        .build()
        .toString()
        .trimEnd('/')
}

/** `GET {root}/api/v1/tasks/{taskId}`. */
fun dashScopeTaskPath(taskId: String): String = "/api/v1/tasks/$taskId"

/**
 * `POST {root}/api/v1/services/aigc/multimodal-generation/generation` — the **synchronous**
 * DashScope endpoint for the newer Qwen-Image 2.x / 3.x family.
 *
 * Why this exists: Wanx and the first Qwen-Image generation (`qwen-image`, `qwen-image-plus`,
 * `qwen-image-max`) take a plain `input.prompt` on the asynchronous task API
 * ([DASHSCOPE_IMAGE_SYNTHESIS_PATH]). The 2.1-pro and 3.0 models instead use a multimodal
 * **messages** body on this endpoint — one user message whose `content` is a single
 * `{"text": ...}` part. Calling the old endpoint with a new model (or the new endpoint with an
 * old model) makes the gateway answer `400 InvalidParameter` with the message
 * `url error, please check url！`, whose wording misleadingly points at the URL rather than the
 * model. That is exactly how `qwen-image-3.0` failed.
 *
 * The route was confirmed live: `POST` with a bogus key answers `401` (route exists), the same
 * signal used to confirm [DASHSCOPE_IMAGE_SYNTHESIS_PATH].
 */
const val DASHSCOPE_MULTIMODAL_GENERATION_PATH = "/api/v1/services/aigc/multimodal-generation/generation"

/**
 * True when [modelId] belongs to the newer Qwen-Image family served as a multimodal **messages**
 * request on [DASHSCOPE_MULTIMODAL_GENERATION_PATH].
 *
 * Matched by version prefix rather than an exact list so a future `qwen-image-3.x` keeps working:
 * the 2.1-pro / 3.0 series use the messages body, while `qwen-image`, `qwen-image-plus`,
 * `qwen-image-max`, `wanx*` and `wan2.*` still take `input.prompt` on the async task API. The
 * caller still retries the other endpoint when the provider rejects the body (see
 * [isDashScopeEndpointModelMismatch]) so a model we did not enumerate is not left broken.
 */
fun dashScopeUsesMultimodalEndpoint(modelId: String): Boolean {
    val id = modelId.trim().lowercase()
    return id.startsWith("qwen-image-2.") || id.startsWith("qwen-image-3.")
}

/**
 * True when a DashScope image response is the "model does not match this endpoint" rejection:
 * `400 InvalidParameter` carrying the message `url error, please check url！`.
 *
 * Alibaba's error-code reference is explicit that this wording does **not** mean the request URL
 * is wrong — it means the `model` value is not valid *for the endpoint that was called* (a text
 * model on an image API, or a new-generation image model on the legacy prompt API). Callers use
 * this to retry on the family's real endpoint instead of surfacing a misleading error. Never
 * throws.
 */
fun isDashScopeEndpointModelMismatch(responseBody: String): Boolean {
    val lower = responseBody.lowercase()
    return "url error" in lower && "invalidparameter" in lower
}

/**
 * Qwen-Image's native sizes, in DashScope's `WIDTH*HEIGHT` notation (note the `*`, not `x`).
 *
 * Qwen-Image accepts these three; a caller that wants a Wanx-only size can still override it via
 * the model's custom body (`parameters.size`), which is merged last.
 */
fun dashScopeImageSize(aspectRatio: ImageAspectRatio): String = when (aspectRatio) {
    ImageAspectRatio.SQUARE -> "1328*1328"
    ImageAspectRatio.LANDSCAPE -> "1664*928"
    ImageAspectRatio.PORTRAIT -> "928*1664"
}

/** The `image-synthesis` submit body: `{ model, input:{prompt}, parameters:{size, n} }`. */
fun buildDashScopeImageRequestBody(
    model: Model,
    prompt: String,
    aspectRatio: ImageAspectRatio,
    numOfImages: Int,
): JsonObject = buildJsonObject {
    put("model", model.modelId)
    putJsonObject("input") {
        put("prompt", prompt)
    }
    putJsonObject("parameters") {
        put("size", dashScopeImageSize(aspectRatio))
        put("n", numOfImages)
    }
}

/**
 * The `multimodal-generation` sync body for the Qwen-Image 2.x / 3.x family:
 * `{ model, input:{messages:[{role:"user",content:[{text}]}]}, parameters:{size, n} }`.
 *
 * Single-round only (the provider accepts exactly one user message), and `size` keeps DashScope's
 * `WIDTH*HEIGHT` notation — only the OpenAI-compatible variant of these models switches to
 * `WIDTHxHEIGHT`.
 */
fun buildDashScopeMultimodalImageRequestBody(
    model: Model,
    prompt: String,
    aspectRatio: ImageAspectRatio,
    numOfImages: Int,
): JsonObject = buildJsonObject {
    put("model", model.modelId)
    putJsonObject("input") {
        putJsonArray("messages") {
            addJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    addJsonObject { put("text", prompt) }
                }
            }
        }
    }
    putJsonObject("parameters") {
        put("size", dashScopeImageSize(aspectRatio))
        put("n", numOfImages)
    }
}

/**
 * `POST {root}/api/v1/services/aigc/image2image/image-synthesis` (with `X-DashScope-Async: enable`)
 * — the legacy Wanx image-**edit** task API (`wanx2.1-imageedit` and friends).
 *
 * It is the edit counterpart of [DASHSCOPE_IMAGE_SYNTHESIS_PATH]: the source image is passed as
 * `input.base_image_url` (a public URL or a `data:{mime};base64,...` URI) together with a
 * *fixed* `input.function` naming the editing task, rather than as a messages part.
 */
const val DASHSCOPE_IMAGE_EDIT_SYNTHESIS_PATH = "/api/v1/services/aigc/image2image/image-synthesis"

/**
 * The `input.function` the legacy edit API is called with: instruction-based whole-image editing
 * ("make the sky a sunset"), the closest analogue of the prompt-driven multimodal edit. The other
 * function values (`remove_watermark`, `expand`, `colorization`, ...) are task-specific and take
 * extra parameters, so they are deliberately not guessed here — a caller that wants one can pass
 * it through the model's custom body.
 */
const val DASHSCOPE_IMAGE_EDIT_FUNCTION = "description_edit"

/**
 * True when [modelId] is served by the **multimodal** edit endpoint (the sync messages body) —
 * i.e. the modern Qwen-Image edit family plus the 2.x / 3.x generation line, which edits too:
 *
 *  - `qwen-image-edit`, `qwen-image-edit-plus`, `qwen-image-edit-max`
 *  - `qwen-image-2.x` / `qwen-image-3.x`
 *  - `wan2.6-image` (editing mode) and the `wan2.5-i2i` preview
 *
 * Matched by prefix, so a future `qwen-image-edit-*` keeps working. Everything else is assumed to
 * be the legacy async family and is retried on the other endpoint when the provider rejects it (see
 * [isDashScopeEndpointModelMismatch]).
 */
fun dashScopeUsesMultimodalEditEndpoint(modelId: String): Boolean {
    val id = modelId.trim().lowercase()
    return id.startsWith("qwen-image-edit") ||
        dashScopeUsesMultimodalEndpoint(id) ||
        id.startsWith("wan2.6-image") ||
        id.startsWith("wan2.5-i2i")
}

/**
 * True when [modelId] is the legacy async edit family — `wanx2.1-imageedit` and its successors —
 * which takes `input.function` / `input.base_image_url` on
 * [DASHSCOPE_IMAGE_EDIT_SYNTHESIS_PATH] instead of a messages body.
 */
fun dashScopeUsesLegacyEditEndpoint(modelId: String): Boolean {
    val id = modelId.trim().lowercase()
    return id.startsWith("wanx") && "imageedit" in id
}

/**
 * Which edit endpoint to try **first** for [modelId]: `true` for the sync multimodal body, `false`
 * for the legacy async task API. The legacy family is the only one that must not be sent the
 * messages shape, so it is the only case that flips the default; every other id starts on the
 * multimodal endpoint and falls back on a rejection.
 */
fun dashScopePrefersMultimodalEdit(modelId: String): Boolean =
    !dashScopeUsesLegacyEditEndpoint(modelId)

/**
 * The `multimodal-generation` **edit** body: one user message whose `content` is the source
 * image(s) first and the instruction text last —
 * `{ model, input:{messages:[{role:"user",content:[{image}...,{text}]}]}, parameters:{n} }`.
 *
 * `imageDataUris` are `data:{mime};base64,...` URIs, which DashScope accepts wherever it accepts a
 * public image URL. `size` is deliberately **not** sent: the edit endpoint only takes a couple of
 * fixed square sizes (768*768 / 2048*2048) and its natural output follows the source image's
 * aspect, so pinning a size would either be rejected or crop the edit. A caller that wants one can
 * still override it through the model's custom body, which is merged last.
 */
fun buildDashScopeMultimodalEditRequestBody(
    model: Model,
    prompt: String,
    imageDataUris: List<String>,
    numOfImages: Int,
): JsonObject = buildJsonObject {
    put("model", model.modelId)
    putJsonObject("input") {
        putJsonArray("messages") {
            addJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    imageDataUris.forEach { uri -> addJsonObject { put("image", uri) } }
                    addJsonObject { put("text", prompt) }
                }
            }
        }
    }
    putJsonObject("parameters") {
        put("n", numOfImages)
    }
}

/**
 * The legacy async **edit** submit body:
 * `{ model, input:{function, prompt, base_image_url}, parameters:{n} }`.
 *
 * The legacy API takes exactly one source image (a second one would be a `mask_image_url`, a
 * different feature), so [baseImageUri] is the first of the caller's images.
 */
fun buildDashScopeEditRequestBody(
    model: Model,
    prompt: String,
    baseImageUri: String,
    numOfImages: Int,
): JsonObject = buildJsonObject {
    put("model", model.modelId)
    putJsonObject("input") {
        put("function", DASHSCOPE_IMAGE_EDIT_FUNCTION)
        put("prompt", prompt)
        put("base_image_url", baseImageUri)
    }
    putJsonObject("parameters") {
        put("n", numOfImages)
    }
}

/** `output.task_id` of the submit response, or `null` when absent/unparseable. */
fun parseDashScopeTaskId(body: String): String? = dashScopeOutput(body)
    ?.get("task_id")
    ?.jsonPrimitive
    ?.contentOrNull

/** `output.task_status` (`PENDING` / `RUNNING` / `SUCCEEDED` / `FAILED` / `CANCELED` / `UNKNOWN`). */
fun parseDashScopeTaskStatus(body: String): String? = dashScopeOutput(body)
    ?.get("task_status")
    ?.jsonPrimitive
    ?.contentOrNull

/** `output.message` — the human-readable reason a task failed, when the provider supplies one. */
fun parseDashScopeTaskMessage(body: String): String? = dashScopeOutput(body)
    ?.get("message")
    ?.jsonPrimitive
    ?.contentOrNull

/** The result image URLs of a `SUCCEEDED` task; empty when the body has none. */
fun parseDashScopeImageUrls(body: String): List<String> = dashScopeOutput(body)
    ?.get("results")
    ?.jsonArray
    ?.mapNotNull { it.jsonObject["url"]?.jsonPrimitive?.contentOrNull }
    .orEmpty()

/**
 * The image URLs of a successful **synchronous** multimodal response, read from
 * `output.choices[].message.content[].image`.
 *
 * Every `image` part across every choice is collected (a future `n > 1` response may spread
 * results over several parts), and any malformed body yields an empty list rather than throwing.
 */
fun parseDashScopeMultimodalImageUrls(body: String): List<String> = dashScopeOutput(body)
    ?.get("choices")
    ?.jsonArray
    ?.flatMap { choice ->
        choice.jsonObject["message"]?.jsonObject
            ?.get("content")?.jsonArray
            ?.mapNotNull { it.jsonObject["image"]?.jsonPrimitive?.contentOrNull }
            .orEmpty()
    }
    .orEmpty()

/** `output` of a DashScope envelope, or `null` for any malformed body (never throws). */
internal fun dashScopeOutput(body: String): JsonObject? = runCatching {
    json.parseToJsonElement(body).jsonObject["output"]?.jsonObject
}.getOrNull()
