package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.ImageAspectRatio
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * MiniMax（海螺 Hailuo）video generation, on the vendor's **native** async task API.
 *
 * MiniMax is configured as a plain OpenAI-compatible provider
 * (`https://api.minimaxi.com/v1`), whose base URL has no video route, so video goes straight to the
 * native endpoints. Two API generations are in the wild and the **model id decides** which one
 * applies ([minimaxUsesH3Dialect]):
 *
 *  - **v1 / classic** (`MiniMax-Hailuo-2.3`, `MiniMax-Hailuo-02`, `T2V-01*`, `I2V-01*`), a three-step
 *    flow:
 *      1. `POST {root}/v1/video_generation` with a **flat** body
 *         (`{ model, prompt, duration?, resolution?, first_frame_image? }`) → `task_id`;
 *      2. `GET {root}/v1/query/video_generation?task_id=…` → `status` + (on success) `file_id`;
 *      3. `GET {root}/v1/files/retrieve?file_id=…` → `file.download_url`.
 *  - **v2 / H3** (`MiniMax-H3`, `MiniMax-H3-Max`), a two-step flow whose body is a **multimodal
 *    `content[]`** array (`{type: text|image_url|video_url|audio_url}` plus an optional `role`):
 *      1. `POST {root}/v2/video_generation` → `task_id`;
 *      2. `GET {root}/v2/query/video_generation/{task_id}` → `task.status` and, on success,
 *         `task.content.url` — no `file_id` round-trip.
 *
 * ★ `resolution` is a **quality tier** (`768P` / `1080P`, or `768P` / `2K` on H3), not an aspect
 * ratio, so nothing derived from [ImageAspectRatio] is ever sent for it — the vendor default is
 * kept. H3, on the other hand, *requires* `ratio` for text-to-video and pins it to `adaptive` when
 * a first frame is supplied (the frame's aspect wins).
 *
 * Every 200 that carries a `base_resp.status_code != 0` is an error in disguise — see
 * [parseMiniMaxBaseRespMessage]. Everything decidable without a socket lives here so a bare-JVM
 * test can pin it, mirroring [DashScopeVideoRequest] / [VolcengineVideoRequest].
 */

/** MiniMax is reachable on four hosts: `api.minimaxi.com`, `api.minimax.chat`, `api.minimax.cn` and `api.minimax.io`. */
private val MINIMAX_HOST_SUFFIXES = listOf(
    ".minimaxi.com",
    ".minimax.chat",
    ".minimax.cn",
    ".minimax.io",
)

/** The host of [baseUrl] when it is a MiniMax API host, else `null`. */
private fun minimaxHost(baseUrl: String): String? {
    val host = baseUrl.toHttpUrlOrNull()?.host?.lowercase() ?: return null
    if (MINIMAX_HOST_SUFFIXES.none { host.endsWith(it) }) return null
    // The `api.` label is part of every documented host, and requiring it rejects an unrelated
    // tenant host such as `foo.minimax.chat`.
    return if (host.substringBefore('.').startsWith("api")) host else null
}

/** True when [baseUrl] points at a MiniMax API host. */
fun isMiniMaxBaseUrl(baseUrl: String): Boolean = minimaxHost(baseUrl) != null

/**
 * The MiniMax *native* API root for an OpenAI-compatible [baseUrl] — scheme + host (+ non-default
 * port), with any `/v1` path dropped:
 *
 * ```
 * https://api.minimaxi.com/v1   ->   https://api.minimaxi.com
 * ```
 */
fun minimaxNativeRoot(baseUrl: String): String? {
    if (minimaxHost(baseUrl) == null) return null
    val url = baseUrl.toHttpUrlOrNull() ?: return null
    return url.newBuilder()
        .encodedPath("/")
        .query(null)
        .fragment(null)
        .build()
        .toString()
        .trimEnd('/')
}

/** `POST {root}/v1/video_generation` — the flat (classic) create endpoint. */
const val MINIMAX_VIDEO_GENERATION_PATH = "/v1/video_generation"

/** `GET {root}/v1/query/video_generation?task_id={taskId}`. */
fun minimaxVideoQueryUrl(root: String, taskId: String): String =
    "$root$MINIMAX_VIDEO_QUERY_PATH?task_id=$taskId"

/** `/v1/query/video_generation` — the classic task-status route. */
const val MINIMAX_VIDEO_QUERY_PATH = "/v1/query/video_generation"

/** `/v1/files/retrieve` — maps a finished task's `file_id` to a download URL. */
const val MINIMAX_FILE_RETRIEVE_PATH = "/v1/files/retrieve"

/** `GET {root}/v1/files/retrieve?file_id={fileId}`. */
fun minimaxFileRetrieveUrl(root: String, fileId: String): String =
    "$root$MINIMAX_FILE_RETRIEVE_PATH?file_id=$fileId"

/** `POST {root}/v2/video_generation` — the multimodal (H3) create endpoint. */
const val MINIMAX_H3_VIDEO_GENERATION_PATH = "/v2/video_generation"

/** `GET {root}/v2/query/video_generation/{taskId}` — the H3 task-status route (path param, not query). */
fun minimaxH3QueryPath(taskId: String): String = "/v2/query/video_generation/$taskId"

/** Hailuo jobs run ~1–5 minutes; ~10 min of polling budget. */
const val MINIMAX_VIDEO_POLL_ATTEMPTS = 100

/** Delay between two MiniMax task polls — the vendor recommends ~10 s; 6 s keeps the UI honest. */
const val MINIMAX_VIDEO_POLL_INTERVAL_MS = 6_000L

/**
 * True when [modelId] belongs to the **H3** generation, which speaks the `v2` multimodal-`content[]`
 * dialect. Everything else (Hailuo-2.3 / -02, T2V-01*, I2V-01*) uses the classic `v1` flat body.
 */
fun minimaxUsesH3Dialect(modelId: String): Boolean =
    modelId.trim().lowercase().startsWith("minimax-h3")

/**
 * The `ratio` for H3 text-to-video. H3 requires it and refuses `adaptive` without an input image.
 */
fun minimaxH3Ratio(aspectRatio: ImageAspectRatio): String = when (aspectRatio) {
    ImageAspectRatio.LANDSCAPE -> "16:9"
    ImageAspectRatio.PORTRAIT -> "9:16"
    ImageAspectRatio.SQUARE -> "1:1"
}

/**
 * The `duration` to send, or `null` to omit it (the model then applies its own default).
 *
 * The two dialects disagree, so this is stricter than a clamp:
 *  - **H3 / H3-Max** accept an integer 4–15 s (H3) or 5–15 s (H3-Max) → clamped to 4..15;
 *  - **classic** models take only 6 or 10 s → a request is snapped to the nearer of the two;
 *  - no request → omitted for both.
 */
fun minimaxVideoDuration(modelId: String, requested: Int?): Int? {
    if (requested == null) return null
    if (minimaxUsesH3Dialect(modelId)) return requested.coerceIn(4, 15)
    return if (requested >= 8) 10 else 6
}

/**
 * The classic `POST /v1/video_generation` submit body:
 * `{ model, prompt, duration?, first_frame_image? }`.
 *
 * [firstFrameUrl] may be a public URL or a `data:{mime};base64,...` string; `null` keeps the call
 * text-to-video. Same reasoning as [buildDashScopeImageToVideoRequestBody]: the frame is inlined
 * rather than hosted somewhere.
 */
fun buildMiniMaxVideoRequestBody(
    model: Model,
    prompt: String,
    durationSeconds: Int?,
    firstFrameUrl: String?,
): JsonObject = buildJsonObject {
    put("model", model.modelId)
    put("prompt", prompt)
    minimaxVideoDuration(model.modelId, durationSeconds)?.let { put("duration", it) }
    firstFrameUrl?.let { put("first_frame_image", it) }
}

/**
 * The H3 `POST /v2/video_generation` submit body — a multimodal `content[]`:
 *
 * ```json
 * {
 *   "model": "MiniMax-H3",
 *   "content": [
 *     { "type": "text", "text": "…" },
 *     { "type": "image_url", "image_url": { "url": "…" }, "role": "first_frame" }
 *   ],
 *   "duration": 5,
 *   "ratio": "16:9"
 * }
 * ```
 *
 * `ratio` is only sent for text-to-video: with a first frame the aspect follows the image and H3
 * pins `ratio` to `adaptive`.
 */
fun buildMiniMaxH3VideoRequestBody(
    model: Model,
    prompt: String,
    aspectRatio: ImageAspectRatio,
    durationSeconds: Int?,
    firstFrameUrl: String?,
): JsonObject = buildJsonObject {
    put("model", model.modelId)
    putJsonArray("content") {
        addJsonObject {
            put("type", "text")
            put("text", prompt)
        }
        if (firstFrameUrl != null) {
            addJsonObject {
                put("type", "image_url")
                putJsonObject("image_url") {
                    put("url", firstFrameUrl)
                }
                put("role", "first_frame")
            }
        }
    }
    minimaxVideoDuration(model.modelId, durationSeconds)?.let { put("duration", it) }
    put("ratio", if (firstFrameUrl == null) minimaxH3Ratio(aspectRatio) else "adaptive")
}

/** The created task's `task_id`, or `null` when absent/unparseable. Shared by both dialects. */
fun parseMiniMaxTaskId(body: String): String? = miniMaxRoot(body)
    ?.get("task_id")
    ?.jsonPrimitive
    ?.contentOrNull

/** The classic task `status` (`Preparing` / `Queueing` / `Processing` / `Success` / `Fail`), or `null`. */
fun parseMiniMaxTaskStatus(body: String): String? = miniMaxRoot(body)
    ?.get("status")
    ?.jsonPrimitive
    ?.contentOrNull

/** `file_id` of a `Success` task, or `null` when absent. */
fun parseMiniMaxFileId(body: String): String? = miniMaxRoot(body)
    ?.get("file_id")
    ?.jsonPrimitive
    ?.contentOrNull

/** `file.download_url` of `files/retrieve`, or `null` when absent. */
fun parseMiniMaxDownloadUrl(body: String): String? = miniMaxRoot(body)
    ?.get("file")
    ?.jsonObject
    ?.get("download_url")
    ?.jsonPrimitive
    ?.contentOrNull

/** The H3 task `status` (`succeeded` / `failed` / `cancelled` / …), or `null`. */
fun parseMiniMaxH3TaskStatus(body: String): String? = miniMaxH3Task(body)
    ?.get("status")
    ?.jsonPrimitive
    ?.contentOrNull

/** `task.content.url` of a finished H3 task, or `null` when absent. */
fun parseMiniMaxH3VideoUrl(body: String): String? = miniMaxH3Task(body)
    ?.get("content")
    ?.jsonObject
    ?.get("url")
    ?.jsonPrimitive
    ?.contentOrNull

/** `task.error.message` of a failed H3 task, when the vendor supplies one. */
fun parseMiniMaxH3ErrorMessage(body: String): String? = miniMaxH3Task(body)
    ?.get("error")
    ?.jsonObject
    ?.get("message")
    ?.jsonPrimitive
    ?.contentOrNull

/**
 * The message of a `base_resp` failure, or `null` when the response carries no failure.
 *
 * MiniMax answers **HTTP 200** for rejected tasks and reports the problem only inside
 * `base_resp.status_code` / `status_msg`, so a missing check would let a dead task look queued.
 */
fun parseMiniMaxBaseRespMessage(body: String): String? {
    val baseResp = miniMaxRoot(body)?.get("base_resp")?.jsonObject ?: return null
    val code = baseResp["status_code"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return null
    if (code == 0L) return null
    return baseResp["status_msg"]?.jsonPrimitive?.contentOrNull ?: "base_resp.status_code=$code"
}

private fun miniMaxRoot(body: String): JsonObject? =
    runCatching { kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject }.getOrNull()

private fun miniMaxH3Task(body: String): JsonObject? =
    miniMaxRoot(body)?.get("task")?.jsonObject
