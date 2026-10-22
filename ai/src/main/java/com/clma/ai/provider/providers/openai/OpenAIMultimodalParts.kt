package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * D3 — the OpenAI-compatible `content` blocks for audio and video input.
 *
 * The Chat Completions path used to serialise only text and images: an audio or video part fell
 * into the `else -> {}` branch and disappeared without a trace, which is why this client could not
 * send a recording even when the selected model understood one. DashScope — the endpoint the omni
 * models here run on — documents exactly two more block types in its OpenAI-compatible protocol:
 *
 *  - audio: `{"type":"input_audio","input_audio":{"data":<url or Base64 Data URL>,"format":"mp3"}}`
 *  - video: `{"type":"video_url","video_url":{"url":<url or Base64 Data URL>}}`
 *
 * A device-local attachment is a `file://` URL, so it travels as a Base64 Data URL
 * (`data:<mime>;base64,<bytes>`), which both block types accept. The byte encoding itself stays in
 * `FileEncoder` (Android) and is injected as [encode] — so nothing is read at all when the model
 * cannot take the part, and the block shape stays unit-testable without a device.
 *
 * Pure by construction: kotlinx.serialization only, no Android, no coroutines.
 */
object OpenAIMultimodalParts {

    /** Shown in place of an audio part the current model does not declare support for. */
    const val AUDIO_UNSUPPORTED_PLACEHOLDER =
        "[Audio omitted: the current model does not declare audio input]"

    /** Shown in place of a video part the current model does not declare support for. */
    const val VIDEO_UNSUPPORTED_PLACEHOLDER =
        "[Video omitted: the current model does not declare video input]"

    /** Shown when the attachment exists but its bytes could not be read/encoded. */
    const val AUDIO_UNREADABLE_PLACEHOLDER = "[Audio attachment could not be read]"
    const val VIDEO_UNREADABLE_PLACEHOLDER = "[Video attachment could not be read]"

    /** Container assumed when a file name carries no recognisable audio extension. */
    const val DEFAULT_AUDIO_FORMAT = "mp3"

    private val AUDIO_CONTAINERS =
        setOf("mp3", "wav", "m4a", "aac", "flac", "opus", "ogg", "webm", "amr")

    private val AUDIO_MIME = mapOf(
        "mp3" to "audio/mpeg",
        "wav" to "audio/wav",
        "m4a" to "audio/mp4",
        "aac" to "audio/aac",
        "flac" to "audio/flac",
        "opus" to "audio/opus",
        "ogg" to "audio/ogg",
        "webm" to "audio/webm",
        "amr" to "audio/amr",
    )

    private val VIDEO_MIME = mapOf(
        "mp4" to "video/mp4",
        "m4v" to "video/x-m4v",
        "webm" to "video/webm",
        "mov" to "video/quicktime",
        "mkv" to "video/x-matroska",
        "3gp" to "video/3gpp",
    )

    /** Lowercased extension of [url] with query/fragment stripped; "" when there is none. */
    fun extensionOf(url: String): String =
        url.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase()

    /**
     * `input_audio.format` — the container's own extension, or [DEFAULT_AUDIO_FORMAT] when it is
     * not one we recognise. DashScope documents mp3/wav by example; forwarding the real container
     * is what lets the server pick the right decoder instead of being handed a guess.
     */
    fun audioFormatOf(url: String): String =
        extensionOf(url).takeIf { it in AUDIO_CONTAINERS } ?: DEFAULT_AUDIO_FORMAT

    /**
     * The `input_audio` block for one audio part, or a text placeholder when it cannot go out.
     *
     * [encode] returns RAW base64 (no `data:` prefix) and is only called when the model declares
     * audio input, so an unsupported model never pays to read the file.
     */
    fun audioContentPart(
        supportsAudioInput: Boolean,
        url: String,
        encode: () -> String?,
    ): JsonObject {
        if (!supportsAudioInput) return textPart(AUDIO_UNSUPPORTED_PLACEHOLDER)
        val raw = encode() ?: return textPart(AUDIO_UNREADABLE_PLACEHOLDER)
        val format = audioFormatOf(url)
        return buildJsonObject {
            put("type", "input_audio")
            put("input_audio", buildJsonObject {
                put("data", dataUrl(AUDIO_MIME[format] ?: "audio/$format", raw))
                put("format", format)
            })
        }
    }

    /**
     * The `video_url` block for one video part, or a text placeholder when it cannot go out.
     * [encode] returns RAW base64, exactly as in [audioContentPart].
     */
    fun videoContentPart(
        supportsVideoInput: Boolean,
        url: String,
        encode: () -> String?,
    ): JsonObject {
        if (!supportsVideoInput) return textPart(VIDEO_UNSUPPORTED_PLACEHOLDER)
        val raw = encode() ?: return textPart(VIDEO_UNREADABLE_PLACEHOLDER)
        val mime = VIDEO_MIME[extensionOf(url)] ?: "video/mp4"
        return buildJsonObject {
            put("type", "video_url")
            put("video_url", buildJsonObject {
                put("url", dataUrl(mime, raw))
            })
        }
    }

    private fun textPart(text: String): JsonObject = buildJsonObject {
        put("type", "text")
        put("text", text)
    }

    private fun dataUrl(mime: String, rawBase64: String): String = "data:$mime;base64,$rawBase64"
}
