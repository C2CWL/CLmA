package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `backgroundMode` carries `@EncodeDefault(NEVER)`: an assistant on the default AUTO mode is
 * stored exactly as before, and an older store without the key reads back as AUTO.
 */
class AssistantBackgroundModeSerializationTest {

    @Test
    fun `the default mode is not written to json`() {
        val json = JsonInstant.encodeToString(Assistant())
        assertFalse(json.contains("backgroundMode"))
    }

    @Test
    fun `a stored assistant without the key reads back as auto`() {
        val json = JsonInstant.encodeToString(Assistant(background = "file://a", darkBackground = "file://d"))
        assertFalse(json.contains("backgroundMode"))
        assertEquals(AssistantBackgroundMode.AUTO, JsonInstant.decodeFromString<Assistant>(json).backgroundMode)
    }

    @Test
    fun `general mode round-trips`() {
        val encoded = JsonInstant.encodeToString(Assistant(backgroundMode = AssistantBackgroundMode.GENERAL))
        assertTrue(encoded.contains("\"backgroundMode\":\"GENERAL\""))
        assertEquals(
            AssistantBackgroundMode.GENERAL,
            JsonInstant.decodeFromString<Assistant>(encoded).backgroundMode,
        )
    }
}
