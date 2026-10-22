package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.ImageAspectRatio

/**
 * DashScope (Aliyun Bailian) **text-to-video** on the provider's native API — the Wan (万相) family.
 *
 * Same story as [DashScopeImageRequest]: DashScope is configured as a plain OpenAI-compatible
 * provider (`https://dashscope.aliyuncs.com/compatible-mode/v1`), whose base URL has no video
 * route, so Wan goes through the native async task API:
 *
 *  1. `POST {root}/api/v1/services/aigc/video-generation/video-synthesis`, header
 *     `X-DashScope-Async: enable`, body `{ model, input:{prompt}, parameters:{size, duration?} }`
 *     → `output.task_id`;
 *  2. poll `GET {root}/api/v1/tasks/{id}` (shared with the image path) until `SUCCEEDED`, then read
 *     `output.video_url`.
 *
 * Root derivation and task polling reuse the helpers already in [DashScopeImageRequest]
 * ([dashScopeNativeRoot], [dashScopeTaskPath], [parseDashScopeTaskId], [parseDashScopeTaskStatus],
 * [parseDashScopeTaskMessage]) — the two families share one task API. Everything that can be
 * decided without a socket lives here so a bare-JVM test can pin it.
 */

/** `POST {root}/api/v1/services/aigc/video-generation/video-synthesis` (with `X-DashScope-Async: enable`). */
const val DASHSCOPE_VIDEO_SYNTHESIS_PATH = "/api/v1/services/aigc/video-generation/video-synthesis"

/**
 * `POST {root}/api/v1/services/aigc/image2video/video-synthesis` — the **first-frame** (i2v) sibling
 * of the text-to-video path. Same async task API (one `task_id`, polled on the shared
 * `/api/v1/tasks/{id}`), but the frame goes in `input.img_url` and resolution is a quality tier
 * (`480P` / `720P` / `1080P`) rather than a `size`; the output keeps the first frame's aspect ratio,
 * so [dashScopeVideoSize] does not apply here.
 */
const val DASHSCOPE_IMAGE2VIDEO_SYNTHESIS_PATH = "/api/v1/services/aigc/image2video/video-synthesis"

/** Video jobs take 1–5 minutes, so this is much longer than the image poll budget (~9 min). */
const val DASHSCOPE_VIDEO_POLL_ATTEMPTS = 90

/** Delay between two video `GET /api/v1/tasks/{id}` polls. */
const val DASHSCOPE_VIDEO_POLL_INTERVAL_MS = 6_000L

/**
 * Wan's accepted `parameters.size` values, in the provider's `WIDTH*HEIGHT` notation (a literal
 * `*`, not `x`). The docs list a whole matrix per resolution tier; these are the 720P entries for
 * the three shapes the tool exposes (`960*960` is Wan's 1:1 at 720P — **not** `624*624`, which
 * only exists at 480P, nor `1440*1440`, which is 1080P).
 */
fun dashScopeVideoSize(aspectRatio: ImageAspectRatio): String = when (aspectRatio) {
    ImageAspectRatio.LANDSCAPE -> "1280*720"
    ImageAspectRatio.PORTRAIT -> "720*1280"
    ImageAspectRatio.SQUARE -> "960*960"
}

/**
 * The `parameters.duration` to send for [modelId], or `null` to omit it.
 *
 * Duration is model-dependent and this is stricter than a plain clamp because the tiers disagree:
 *  - `wan2.1*` / `wan2.2*` / `wanx2.1*` are **fixed at 5 s** and reject the parameter, so it is
 *    omitted even when one was requested;
 *  - `wan2.5*` accepts only 5 or 10 → a request is snapped to the nearer of the two;
 *  - `wan2.6*` (and anything unrecognised) accepts 2–15 → clamped;
 *  - no request → omitted (the model applies its own default).
 */
fun dashScopeVideoDuration(modelId: String, requested: Int?): Int? {
    val id = modelId.trim().lowercase()
    if (id.startsWith("wan2.1") || id.startsWith("wan2.2") || id.startsWith("wanx2.1")) return null
    if (requested == null) return null
    if (id.startsWith("wan2.5")) return if (requested >= 8) 10 else 5
    return requested.coerceIn(2, 15)
}

/** The `video-synthesis` submit body: `{ model, input:{prompt}, parameters:{size, duration?} }`. */
fun buildDashScopeVideoRequestBody(
    model: Model,
    prompt: String,
    aspectRatio: ImageAspectRatio,
    durationSeconds: Int?,
): JsonObject = buildJsonObject {
    put("model", model.modelId)
    putJsonObject("input") {
        put("prompt", prompt)
    }
    putJsonObject("parameters") {
        put("size", dashScopeVideoSize(aspectRatio))
        dashScopeVideoDuration(model.modelId, durationSeconds)?.let { put("duration", it) }
    }
}

/** `output.video_url` of a finished task, or `null` when absent/unparseable. */
fun parseDashScopeVideoUrl(body: String): String? = dashScopeOutput(body)
    ?.get("video_url")
    ?.jsonPrimitive
    ?.contentOrNull

/**
 * The first-frame (i2v) submit body: `{ model, input:{prompt, img_url}, parameters:{duration?} }`.
 *
 * [firstFrameUrl] may be a public HTTP(S) URL or a `data:{mime};base64,...` string — DashScope
 * accepts both, which is why the caller inlines the picked file rather than hosting it.
 */
fun buildDashScopeImageToVideoRequestBody(
    model: Model,
    prompt: String,
    firstFrameUrl: String,
    durationSeconds: Int?,
): JsonObject {
    val duration = dashScopeVideoDuration(model.modelId, durationSeconds)
    return buildJsonObject {
        put("model", model.modelId)
        putJsonObject("input") {
            put("prompt", prompt)
            put("img_url", firstFrameUrl)
        }
        // Resolution is a quality tier for i2v and the output follows the first frame's aspect, so
        // `size` is deliberately not sent; only an accepted duration is passed through.
        if (duration != null) {
            putJsonObject("parameters") {
                put("duration", duration)
            }
        }
    }
}
