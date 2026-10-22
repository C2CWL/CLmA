package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.ImageAspectRatio
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 硅基流动 SiliconFlow video generation — an aggregator that fronts many open video models
 * (`Wan-AI/Wan2.2-*`, `tencent/HunyuanVideo`, …) — on the vendor's native submit/poll API.
 *
 * SiliconFlow is configured as an OpenAI-compatible provider (`https://api.siliconflow.cn/v1`),
 * and its video routes hang off that same `/v1`, but they are not part of the OpenAI surface
 * (`/v1/videos` is absent), so they are driven directly:
 *
 *  1. `POST {root}/v1/video/submit` with `Authorization: Bearer <key>` and
 *     `{ model, prompt, image_size, image?, negative_prompt?, seed? }` → `{ requestId }`;
 *  2. `POST {root}/v1/video/status` with `{ requestId }` until `status == "Succeed"`, then read
 *     `results.videos[0].url`.
 *
 * ★ The status call is a **POST with a JSON body**, not a GET with a query param — the one thing
 * that differs most from the other vendors here.
 *
 * `image_size` is required and is an enum of exactly three pixel pairs (`1280x720` / `720x1280` /
 * `960x960`), which lines up with [dashScopeVideoSize]'s three shapes. The result URL is short-lived
 * (docs: one hour), so the caller downloads the clip immediately.
 *
 * Everything decidable without a socket lives here so a bare-JVM test can pin it, mirroring
 * [DashScopeVideoRequest] / [VolcengineVideoRequest] / [MiniMaxVideoRequest] / [ZhipuVideoRequest].
 */

/** SiliconFlow is reachable on `api.siliconflow.cn` (mainland) and `api.siliconflow.com`. */
private val SILICONFLOW_HOST_SUFFIXES = listOf(".siliconflow.cn", ".siliconflow.com")

/** The host of [baseUrl] when it is a SiliconFlow API host, else `null`. */
private fun siliconFlowHost(baseUrl: String): String? {
    val host = baseUrl.toHttpUrlOrNull()?.host?.lowercase() ?: return null
    if (SILICONFLOW_HOST_SUFFIXES.none { host.endsWith(it) }) return null
    return if (host.substringBefore('.').startsWith("api")) host else null
}

/** True when [baseUrl] points at a SiliconFlow API host. */
fun isSiliconFlowBaseUrl(baseUrl: String): Boolean = siliconFlowHost(baseUrl) != null

/**
 * The SiliconFlow *native* API root for an OpenAI-compatible [baseUrl] — scheme + host
 * (+ non-default port), with any `/v1` path dropped:
 *
 * ```
 * https://api.siliconflow.cn/v1   ->   https://api.siliconflow.cn
 * ```
 */
fun siliconFlowNativeRoot(baseUrl: String): String? {
    if (siliconFlowHost(baseUrl) == null) return null
    val url = baseUrl.toHttpUrlOrNull() ?: return null
    return url.newBuilder()
        .encodedPath("/")
        .query(null)
        .fragment(null)
        .build()
        .toString()
        .trimEnd('/')
}

/** `POST {root}/v1/video/submit`. */
const val SILICONFLOW_VIDEO_SUBMIT_PATH = "/v1/video/submit"

/** `POST {root}/v1/video/status` — a POST with a `{"requestId": …}` body, not a GET. */
const val SILICONFLOW_VIDEO_STATUS_PATH = "/v1/video/status"

/** SiliconFlow video jobs run ~1–5 minutes; ~10 min of polling budget. */
const val SILICONFLOW_VIDEO_POLL_ATTEMPTS = 100

/** Delay between two `video/status` polls. */
const val SILICONFLOW_VIDEO_POLL_INTERVAL_MS = 6_000L

/** SiliconFlow's `image_size` enum — the only three values the submit endpoint accepts. */
fun siliconFlowImageSize(aspectRatio: ImageAspectRatio): String = when (aspectRatio) {
    ImageAspectRatio.LANDSCAPE -> "1280x720"
    ImageAspectRatio.PORTRAIT -> "720x1280"
    ImageAspectRatio.SQUARE -> "960x960"
}

/**
 * The `video/submit` body: `{ model, prompt, image_size, image? }`.
 *
 * [firstFrameImage] (a public URL, or bare base64 via [inlineImagePayload]) adds the `image` field,
 * turning the call into image-to-video; `image_size` is documented as required either way, so it is
 * always sent.
 */
fun buildSiliconFlowVideoRequestBody(
    model: Model,
    prompt: String,
    aspectRatio: ImageAspectRatio,
    firstFrameImage: String?,
): JsonObject = buildJsonObject {
    put("model", model.modelId)
    put("prompt", prompt)
    put("image_size", siliconFlowImageSize(aspectRatio))
    firstFrameImage?.let { put("image", it) }
}

/** The `video/status` request body — just the handle the submit call returned. */
fun buildSiliconFlowStatusRequestBody(requestId: String): JsonObject = buildJsonObject {
    put("requestId", requestId)
}

/** The submit response's `requestId`, or `null` when absent/unparseable. */
fun parseSiliconFlowRequestId(body: String): String? {
    val root = siliconFlowRoot(body) ?: return null
    return root.string("requestId") ?: root.string("request_id")
}

/** The task `status` (`Succeed` / `InQueue` / `InProgress` / `Failed`), or `null` when absent. */
fun parseSiliconFlowStatus(body: String): String? = siliconFlowRoot(body)?.string("status")

/**
 * The finished clip's URL, or `null` when absent.
 *
 * The documented home is `results.videos[0].url`; the two fallbacks cover the shape some gateway
 * deployments of the same API answer with.
 */
fun parseSiliconFlowVideoUrl(body: String): String? {
    val root = siliconFlowRoot(body) ?: return null
    val fromResults = root["results"]
        ?.let { results ->
            results.jsonObjectOrNull()
                ?.get("videos")
                ?.jsonArrayOrNull()
                ?.firstOrNull()
                ?.jsonObjectOrNull()
                ?.string("url")
        }
    return fromResults
        ?: root["videos"]?.jsonArrayOrNull()?.firstOrNull()?.jsonObjectOrNull()?.string("url")
        ?: root.string("url")
}

/** The failure explanation (`reason`, else `message`), when the vendor supplies one. */
fun parseSiliconFlowErrorMessage(body: String): String? {
    val root = siliconFlowRoot(body) ?: return null
    return root.string("reason") ?: root.string("message")
}

private fun siliconFlowRoot(body: String): JsonObject? =
    runCatching { kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject }.getOrNull()

private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject

private fun kotlinx.serialization.json.JsonElement.jsonArrayOrNull(): kotlinx.serialization.json.JsonArray? =
    this as? kotlinx.serialization.json.JsonArray
