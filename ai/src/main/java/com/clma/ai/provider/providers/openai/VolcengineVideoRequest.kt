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
 * Volcengine Ark (火山方舟) **text-to-video** — the Doubao Seedance (豆包 Seedance) family.
 *
 * Ark is OpenAI-*compatible* for chat (`https://ark.cn-beijing.volces.com/api/v3`), which is exactly
 * how a user configures it in this app, but video generation is a **native** contents task API and
 * not part of the OpenAI surface, so it is driven directly:
 *
 *  1. `POST {baseUrl}/contents/generations/tasks` with `Authorization: Bearer <key>`,
 *     body `{ model, content:[{type:"text", text}] }` → `{ id }`;
 *  2. `GET {baseUrl}/contents/generations/tasks/{id}` until `status == "succeeded"`, then read
 *     `content.video_url`.
 *
 * Two request dialects exist and the model id decides which one applies:
 *  - **Seedance 1.0** (`doubao-seedance-1-0-*`) takes its knobs as **inline text commands**
 *    (`--ratio 16:9 --dur 5 --resolution 720p --fps 24`) appended to the prompt;
 *  - **Seedance 1.5+ / 2.x** take them as **top-level body fields** (`ratio`, `duration`,
 *    `resolution`, `fps`).
 *
 * The output URL expires (Ark: 24 h), so the caller downloads the clip immediately. Everything
 * decidable without a socket lives here for a bare-JVM test.
 */

private const val VOLCES_HOST_SUFFIX = ".volces.com"

/** BytePlus hosts the international ModelArk region under this domain (`ark.<region>.bytepluses.com`). */
private const val BYTEPLUS_HOST_SUFFIX = ".bytepluses.com"

/** The host of [baseUrl] when it is a Volcengine Ark host, else `null`. */
private fun arkHost(baseUrl: String): String? {
    val host = baseUrl.toHttpUrlOrNull()?.host?.lowercase() ?: return null
    if (!host.endsWith(VOLCES_HOST_SUFFIX) && !host.endsWith(BYTEPLUS_HOST_SUFFIX)) return null
    val firstLabel = host.substringBefore('.')
    return if (firstLabel.startsWith("ark")) host else null
}

/**
 * True when [baseUrl] points at a Volcengine Ark host — the mainland `ark.cn-beijing.volces.com`
 * or the international BytePlus ModelArk host (`ark.<region>.bytepluses.com`).
 */
fun isVolcengineArkBaseUrl(baseUrl: String): Boolean = arkHost(baseUrl) != null

/** `POST {baseUrl}/contents/generations/tasks`. */
const val VOLCENGINE_CREATE_VIDEO_TASK_PATH = "/contents/generations/tasks"

/** `GET {baseUrl}/contents/generations/tasks/{taskId}`. */
fun volcengineVideoTaskPath(taskId: String): String = "/contents/generations/tasks/$taskId"

/** Seedance jobs run ~30–180 s; ~10 min of polling budget. */
const val VOLCENGINE_VIDEO_POLL_ATTEMPTS = 100

/** Delay between two Ark task polls. */
const val VOLCENGINE_VIDEO_POLL_INTERVAL_MS = 6_000L

/** Ark's `resolution` enum — a lowercase tier string, not a pixel size. */
fun volcengineVideoResolution(aspectRatio: ImageAspectRatio): String = when (aspectRatio) {
    ImageAspectRatio.LANDSCAPE, ImageAspectRatio.PORTRAIT, ImageAspectRatio.SQUARE -> "720p"
}

/** Ark's `ratio` enum. */
fun volcengineVideoRatio(aspectRatio: ImageAspectRatio): String = when (aspectRatio) {
    ImageAspectRatio.LANDSCAPE -> "16:9"
    ImageAspectRatio.PORTRAIT -> "9:16"
    ImageAspectRatio.SQUARE -> "1:1"
}

/** Seedance accepts 2–12 s; `null` keeps the vendor default. */
fun volcengineVideoDuration(requested: Int?): Int? = requested?.coerceIn(2, 12)

/**
 * True when [modelId] is a Seedance **1.0** model, whose knobs go inside the text prompt rather
 * than the body. Everything else (1.5+, 2.x, and future ids) uses the top-level fields.
 */
fun volcengineUsesTextCommands(modelId: String): Boolean =
    modelId.trim().lowercase().contains("seedance-1-0")

/** The flags Seedance 1.0 reads out of the prompt text. */
internal fun volcengineTextCommands(
    aspectRatio: ImageAspectRatio,
    durationSeconds: Int?,
    fps: Int,
): String = buildString {
    append(" --ratio ").append(volcengineVideoRatio(aspectRatio))
    durationSeconds?.let { append(" --dur ").append(it) }
    append(" --resolution ").append(volcengineVideoResolution(aspectRatio))
    append(" --fps ").append(fps)
}

/**
 * The `contents/generations/tasks` submit body. For Seedance 1.0 the knobs are folded into the
 * prompt text; for everyone else they ride as top-level fields (and the prompt is left untouched).
 *
 * [firstFrameUrl] (a public URL or `data:{mime};base64,...`) adds an `image_url` content part with
 * `role: first_frame`, turning the request into image-to-video; `null` keeps it text-to-video.
 */
fun buildVolcengineVideoRequestBody(
    model: Model,
    prompt: String,
    aspectRatio: ImageAspectRatio,
    durationSeconds: Int?,
    fps: Int = 24,
    firstFrameUrl: String? = null,
): JsonObject {
    val duration = volcengineVideoDuration(durationSeconds)
    val usesTextCommands = volcengineUsesTextCommands(model.modelId)
    val text = if (usesTextCommands) {
        prompt + volcengineTextCommands(aspectRatio, duration, fps)
    } else {
        prompt
    }
    return buildJsonObject {
        put("model", model.modelId)
        putJsonArray("content") {
            addJsonObject {
                put("type", "text")
                put("text", text)
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
        if (!usesTextCommands) {
            put("ratio", volcengineVideoRatio(aspectRatio))
            put("resolution", volcengineVideoResolution(aspectRatio))
            duration?.let { put("duration", it) }
            put("fps", fps)
        }
    }
}

/** The created task's `id`, or `null` when absent/unparseable. */
fun parseVolcengineTaskId(body: String): String? = runCatching {
    kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
}.getOrNull()?.get("id")?.jsonPrimitive?.contentOrNull

/** The task `status` (`queued` / `running` / `succeeded` / `failed` / `expired`), or `null`. */
fun parseVolcengineTaskStatus(body: String): String? = runCatching {
    kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
}.getOrNull()?.get("status")?.jsonPrimitive?.contentOrNull

/** `content.video_url` of a finished task, or `null` when absent/unparseable. */
fun parseVolcengineVideoUrl(body: String): String? = runCatching {
    kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
}.getOrNull()?.get("content")?.jsonObject?.get("video_url")?.jsonPrimitive?.contentOrNull

/** `error.message` of a failed submit/poll, when the provider supplies one. */
fun parseVolcengineErrorMessage(body: String): String? = runCatching {
    kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
}.getOrNull()?.get("error")?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
