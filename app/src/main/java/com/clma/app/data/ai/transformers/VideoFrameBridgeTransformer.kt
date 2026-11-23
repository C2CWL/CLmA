package com.clma.app.data.ai.transformers

import com.clma.ai.provider.Model
import com.clma.ai.provider.Modality
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import com.clma.app.utils.VideoFrameExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Bridges a video attachment into sparse JPEG keyframes + timestamp text for models
 * that cannot take native video input. Only the copy sent to the provider is rewritten:
 * the stored chat message keeps its UIMessagePart.Video, so existing video rendering,
 * history and the VideoAttachmentTransformer fallback are unaffected.
 *
 * Must run after OcrTransformer (its OCR only targets `file:` images) and before
 * VideoAttachmentTransformer, which downgrades any Video left over on failure to a
 * "cannot see this video" notice.
 */
internal fun shouldBridgeVideoToFrames(model: Model): Boolean =
    Modality.VIDEO !in model.inputModalities && Modality.IMAGE in model.inputModalities

object VideoFrameBridgeTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        // Only bridge when the keyframes can actually reach the model. For a text-only
        // model ChatCompletionsAPI rewrites every Image part into an "image unsupported"
        // placeholder, so extracting frames would be wasted work AND would consume the
        // Video part that drives VideoAttachmentTransformer's explicit-path notice.
        // Leaving the Video untouched here lets the downstream transformer produce that
        // notice with the recognize_local_media recovery guidance.
        if (!shouldBridgeVideoToFrames(ctx.model)) return messages
        if (messages.none { message -> message.parts.any { it is UIMessagePart.Video } }) {
            return messages
        }
        return withContext(Dispatchers.IO) {
            var budget = VideoFrameExtractor.MAX_FRAMES_PER_REQUEST
            messages.map { message ->
                if (message.parts.none { it is UIMessagePart.Video }) return@map message
                val parts = mutableListOf<UIMessagePart>()
                for (part in message.parts) {
                    currentCoroutineContext().ensureActive()
                    if (part !is UIMessagePart.Video) {
                        parts += part
                        continue
                    }
                    if (budget < VideoFrameExtractor.MIN_FRAMES) {
                        parts += part
                        continue
                    }
                    val frames = VideoFrameExtractor.extract(
                        ctx.context,
                        part.url,
                        maxFrames = minOf(VideoFrameExtractor.TARGET_FRAMES, budget),
                    )
                    if (frames.size < VideoFrameExtractor.MIN_FRAMES) {
                        // Failure/short clip: keep the original Video so the downstream
                        // VideoAttachmentTransformer downgrades it to a notice.
                        parts += part
                        continue
                    }
                    budget -= frames.size
                    parts += UIMessagePart.Text(
                        "[Sparse video keyframe preview: ${frames.size} sync frames, JPEG, no audio. " +
                            "These are stills at the timestamps below, not native video; events " +
                            "between frames may be missed and continuous motion must not be inferred. " +
                            "The original video remains attached in the conversation.]"
                    )
                    frames.forEach { frame ->
                        parts += UIMessagePart.Text(frame.timestampText)
                        parts += UIMessagePart.Image(frame.imageUrl)
                    }
                }
                message.copy(parts = parts)
            }
        }
    }
}
