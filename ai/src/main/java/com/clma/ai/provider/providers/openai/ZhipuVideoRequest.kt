package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.ImageAspectRatio
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 智谱 BigModel（Z.AI）video generation — the CogVideoX / 清影 family — on the vendor's native
 * **asynchronous** API.
 *
 * Zhipu is configured as an OpenAI-compatible provider (`https://open.bigmodel.cn/api/paas/v4`,
 * or `https://api.z.ai/api/paas/v4` internationally), and the video route lives on the *same*
 * `/paas/v4` base as chat, but it is not part of the OpenAI surface, so it is driven directly:
 *
 *  1. `POST {root}/api/paas/v4/videos/generations` with `Authorization: Bearer <key>` and
 *     `{ model, prompt, image_url?, size?, duration? }` → `{ id, request_id, task_status }`;
 *  2. `GET {root}/api/paas/v4/async-result/{id}` until `task_status` is `SUCCESS`, then read
 *     `video_result[0].url`.
 *
 * ★ `id` is the polling handle (`request_id` is a separate, more opaque id) — [parseZhipuTaskId]
 * prefers `id` and only falls back to `request_id`.
 *
 * `duration` accepts **5 or 10 s** only, so a request is snapped; `size` is a pixel pair
 * (`1920x1080`), not a tier, and is **omitted for image-to-video** so the output follows the source
 * image — the same call DashScope's i2v path makes. The result URL expires, so the caller downloads
 * the clip immediately from the poll response.
 *
 * Everything decidable without a socket lives here so a bare-JVM test can pin it, mirroring
 * [DashScopeVideoRequest] / [VolcengineVideoRequest] / [MiniMaxVideoRequest].
 */

/** Zhipu is reachable on `*.bigmodel.cn` (mainland) and `*.z.ai` (international, a.k.a. Z.AI). */
private fun zhipuHost(baseUrl: String): String? {
    val host = baseUrl.toHttpUrlOrNull()?.host?.lowercase() ?: return null
    val firstLabel = host.substringBefore('.')
    return when {
        host.endsWith(".bigmodel.cn") && (firstLabel == "open" || firstLabel == "api") -> host
        host.endsWith(".z.ai") && firstLabel == "api" -> host
        else -> null
    }
}

/**
 * The Zhipu *native* API root for an OpenAI-compatible [baseUrl] — scheme + host (+ non-default
 * port), with any `/api/paas/v4` path dropped:
 *
 * ```
 * https://open.bigmodel.cn/api/paas/v4   ->   https://open.bigmodel.cn
 * ```
 */
fun zhipuNativeRoot(baseUrl: String): String? {
    if (zhipuHost(baseUrl) == null) return null
    val url = baseUrl.toHttpUrlOrNull() ?: return null
    return url.newBuilder()
        .encodedPath("/")
        .query(null)
        .fragment(null)
        .build()
        .toString()
        .trimEnd('/')
}

/** True when [baseUrl] points at a Zhipu / Z.AI host. */
fun isZhipuBaseUrl(baseUrl: String): Boolean = zhipuHost(baseUrl) != null

/** `POST {root}/api/paas/v4/videos/generations`. */
const val ZHIPU_VIDEO_GENERATION_PATH = "/api/paas/v4/videos/generations"

/** `GET {root}/api/paas/v4/async-result/{id}`. */
fun zhipuAsyncResultPath(taskId: String): String = "/api/paas/v4/async-result/$taskId"

/** CogVideoX jobs run ~1–3 minutes; ~10 min of polling budget. */
const val ZHIPU_VIDEO_POLL_ATTEMPTS = 100

/** Delay between two `async-result` polls. */
const val ZHIPU_VIDEO_POLL_INTERVAL_MS = 6_000L

/**
 * CogVideoX's accepted `size` values, one per shape the tool exposes (`1024x1024` is the documented
 * square entry; the landscape/portrait entries are Zhipu's 1080p-class pairs).
 */
fun zhipuVideoSize(aspectRatio: ImageAspectRatio): String = when (aspectRatio) {
    ImageAspectRatio.LANDSCAPE -> "1920x1080"
    ImageAspectRatio.PORTRAIT -> "1080x1920"
    ImageAspectRatio.SQUARE -> "1024x1024"
}

/** CogVideoX accepts 5 or 10 s; `null` keeps the vendor default (5 s). */
fun zhipuVideoDuration(requested: Int?): Int? =
    requested?.let { if (it >= 8) 10 else 5 }

/**
 * The `videos/generations` submit body.
 *
 * [firstFrameImage] (a public URL, or bare base64 via [inlineImagePayload]) turns the call into
 * image-to-video and **suppresses `size`** — the clip then follows the source image's aspect ratio.
 */
fun buildZhipuVideoRequestBody(
    model: Model,
    prompt: String,
    aspectRatio: ImageAspectRatio,
    durationSeconds: Int?,
    firstFrameImage: String?,
): JsonObject = buildJsonObject {
    put("model", model.modelId)
    put("prompt", prompt)
    if (firstFrameImage != null) {
        put("image_url", firstFrameImage)
    } else {
        put("size", zhipuVideoSize(aspectRatio))
    }
    zhipuVideoDuration(durationSeconds)?.let { put("duration", it) }
}

/** The created task's polling id (`id`, falling back to `request_id`), or `null`. */
fun parseZhipuTaskId(body: String): String? {
    val root = zhipuRoot(body) ?: return null
    return root.string("id") ?: root.string("request_id")
}

/** The task `task_status` (`PROCESSING` / `SUCCESS` / `FAIL`), or `null` when absent. */
fun parseZhipuTaskStatus(body: String): String? = zhipuRoot(body)?.string("task_status")

/** `video_result[0].url` of a finished task, or `null` when absent. */
fun parseZhipuVideoUrl(body: String): String? = zhipuRoot(body)
    ?.get("video_result")
    ?.jsonArray
    ?.firstOrNull()
    ?.jsonObject
    ?.string("url")

/** `error.message` of a rejected submit / failed task, when the vendor supplies one. */
fun parseZhipuErrorMessage(body: String): String? = zhipuRoot(body)
    ?.get("error")
    ?.jsonObject
    ?.string("message")

private fun zhipuRoot(body: String): JsonObject? =
    runCatching { kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject }.getOrNull()

private fun JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull
