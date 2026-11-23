package com.clma.app.data.ai.transformers

import com.clma.ai.provider.Modality
import com.clma.ai.provider.Model
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bridge must run only when the extracted keyframes can actually be consumed: a
 * model that lacks native video input but does declare image input. For a text-only
 * model, ChatCompletionsAPI rewrites every Image part into an "image unsupported"
 * placeholder, so bridging would both waste decode work and consume the Video part that
 * VideoAttachmentTransformer turns into an explicit-path notice with a
 * recognize_local_media recovery hint.
 */
class VideoFrameBridgeTransformerTest {

    private fun model(vararg modalities: Modality) =
        Model(inputModalities = modalities.toList())

    @Test
    fun `text-only model does not bridge video to frames`() {
        assertFalse(shouldBridgeVideoToFrames(model(Modality.TEXT)))
        assertFalse(shouldBridgeVideoToFrames(model()))
    }

    @Test
    fun `image-capable model without native video bridges video to frames`() {
        assertTrue(shouldBridgeVideoToFrames(model(Modality.TEXT, Modality.IMAGE)))
    }

    @Test
    fun `native video model does not bridge even when it also accepts images`() {
        assertFalse(shouldBridgeVideoToFrames(model(Modality.TEXT, Modality.IMAGE, Modality.VIDEO)))
    }

    @Test
    fun `video input without image input never triggers the frame bridge`() {
        assertFalse(shouldBridgeVideoToFrames(model(Modality.TEXT, Modality.VIDEO)))
    }
}
