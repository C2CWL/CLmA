package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.ImageAspectRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure decisions behind the Volcengine Ark (Seedance) video path. Shapes mirror the live
 * contract: `POST {base}/contents/generations/tasks` returns `{id}`, and
 * `GET {base}/contents/generations/tasks/{id}` returns `{status, content:{video_url}}`.
 *
 * Lives under `app/src/test` because CI runs only the app module's unit tests.
 */
class VolcengineVideoRequestTest {

    // ---- host detection ----

    @Test
    fun ark_hosts_are_recognised() {
        assertTrue(isVolcengineArkBaseUrl("https://ark.cn-beijing.volces.com/api/v3"))
        assertTrue(isVolcengineArkBaseUrl("https://ark.ap-southeast.bytepluses.com/api/v3"))
    }

    @Test
    fun other_hosts_are_not_ark() {
        assertFalse(isVolcengineArkBaseUrl("https://api.openai.com/v1"))
        assertFalse(isVolcengineArkBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1"))
        // A non-ark label on either domain must not match.
        assertFalse(isVolcengineArkBaseUrl("https://notark.cn-beijing.volces.com/api/v3"))
        assertFalse(isVolcengineArkBaseUrl("https://open.volces.com/api/v3"))
        assertFalse(isVolcengineArkBaseUrl("not a url"))
    }

    // ---- mapping ----

    @Test
    fun ratio_and_resolution_map_to_ark_enums() {
        assertEquals("16:9", volcengineVideoRatio(ImageAspectRatio.LANDSCAPE))
        assertEquals("9:16", volcengineVideoRatio(ImageAspectRatio.PORTRAIT))
        assertEquals("1:1", volcengineVideoRatio(ImageAspectRatio.SQUARE))
        assertEquals("720p", volcengineVideoResolution(ImageAspectRatio.LANDSCAPE))
    }

    @Test
    fun duration_is_clamped_into_two_to_twelve() {
        assertNull(volcengineVideoDuration(null))
        assertEquals(2, volcengineVideoDuration(1))
        assertEquals(7, volcengineVideoDuration(7))
        assertEquals(12, volcengineVideoDuration(60))
    }

    // ---- request body ----

    @Test
    fun seedance_10_folds_its_knobs_into_the_prompt_text() {
        assertTrue(volcengineUsesTextCommands("doubao-seedance-1-0-pro-250528"))

        val json = buildVolcengineVideoRequestBody(
            model = Model(modelId = "doubao-seedance-1-0-pro-250528"),
            prompt = "a cat on a beach",
            aspectRatio = ImageAspectRatio.LANDSCAPE,
            durationSeconds = 5,
        ).jsonObject

        assertEquals("doubao-seedance-1-0-pro-250528", json["model"]!!.jsonPrimitive.content)
        val text = json["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text.startsWith("a cat on a beach"))
        assertTrue(text.contains("--ratio 16:9"))
        assertTrue(text.contains("--dur 5"))
        assertTrue(text.contains("--resolution 720p"))
        // The knobs are in the text, so they must NOT also ride as top-level fields.
        assertFalse(json.containsKey("ratio"))
    }

    @Test
    fun newer_models_send_the_knobs_as_top_level_fields() {
        assertFalse(volcengineUsesTextCommands("doubao-seedance-1-5-pro"))

        val json = buildVolcengineVideoRequestBody(
            model = Model(modelId = "doubao-seedance-1-5-pro"),
            prompt = "a cat on a beach",
            aspectRatio = ImageAspectRatio.PORTRAIT,
            durationSeconds = 10,
        ).jsonObject

        assertEquals("a cat on a beach", json["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("9:16", json["ratio"]!!.jsonPrimitive.content)
        assertEquals("720p", json["resolution"]!!.jsonPrimitive.content)
        assertEquals("10", json["duration"]!!.jsonPrimitive.content)
    }

    // ---- response parsing ----

    @Test
    fun task_id_status_and_video_url_are_read_from_the_envelope() {
        assertEquals("cgt-1", parseVolcengineTaskId("""{"id":"cgt-1"}"""))
        assertEquals("running", parseVolcengineTaskStatus("""{"status":"running"}"""))
        assertEquals(
            "https://x/y.mp4",
            parseVolcengineVideoUrl("""{"status":"succeeded","content":{"video_url":"https://x/y.mp4"}}"""),
        )
    }

    @Test
    fun error_message_is_read_when_present() {
        assertEquals("bad req", parseVolcengineErrorMessage("""{"error":{"message":"bad req"}}"""))
        assertNull(parseVolcengineErrorMessage("""{"id":"cgt-1"}"""))
    }

    @Test
    fun parsers_never_throw_on_garbage() {
        assertNull(parseVolcengineTaskId("not json"))
        assertNull(parseVolcengineTaskStatus("not json"))
        assertNull(parseVolcengineVideoUrl("not json"))
        assertNull(parseVolcengineErrorMessage("not json"))
    }

    @Test
    fun create_and_query_paths_match_the_native_endpoints() {
        assertEquals("/contents/generations/tasks", VOLCENGINE_CREATE_VIDEO_TASK_PATH)
        assertEquals("/contents/generations/tasks/cgt-1", volcengineVideoTaskPath("cgt-1"))
    }

    // ---- image-to-video (first frame) ----

    @Test
    fun a_first_frame_adds_an_image_url_content_part() {
        val content = buildVolcengineVideoRequestBody(
            model = Model(modelId = "doubao-seedance-1-5-pro"),
            prompt = "a cat walks",
            aspectRatio = ImageAspectRatio.LANDSCAPE,
            durationSeconds = 5,
            firstFrameUrl = "data:image/png;base64,AAAA",
        ).jsonObject["content"]!!.jsonArray

        assertEquals(2, content.size)
        val frame = content[1].jsonObject
        assertEquals("image_url", frame["type"]!!.jsonPrimitive.content)
        assertEquals("first_frame", frame["role"]!!.jsonPrimitive.content)
        assertEquals("data:image/png;base64,AAAA", frame["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
    }

    @Test
    fun without_a_first_frame_content_holds_only_text() {
        val content = buildVolcengineVideoRequestBody(
            model = Model(modelId = "doubao-seedance-1-5-pro"),
            prompt = "a cat walks",
            aspectRatio = ImageAspectRatio.LANDSCAPE,
            durationSeconds = 5,
        ).jsonObject["content"]!!.jsonArray

        assertEquals(1, content.size)
    }
}
