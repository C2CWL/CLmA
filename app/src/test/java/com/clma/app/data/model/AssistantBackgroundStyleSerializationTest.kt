package com.clma.app.data.model

import com.clma.app.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `backgroundStyle` carries `@EncodeDefault(NEVER)`: classic (default) assistants stay
 * byte-identical in the store; older JSON without the key reads back as CLASSIC.
 */
class AssistantBackgroundStyleSerializationTest {

    @Test
    fun `the default style is not written to json`() {
        val json = JsonInstant.encodeToString(Assistant())
        assertFalse(json.contains("backgroundStyle"))
    }

    @Test
    fun `a stored assistant without the key reads back as classic`() {
        val json = JsonInstant.encodeToString(Assistant(useGradientBackground = true))
        assertFalse(json.contains("backgroundStyle"))
        assertEquals(
            AssistantBackgroundStyle.CLASSIC,
            JsonInstant.decodeFromString<Assistant>(json).backgroundStyle,
        )
    }

    @Test
    fun `starfield style round-trips`() {
        val encoded = JsonInstant.encodeToString(
            Assistant(backgroundStyle = AssistantBackgroundStyle.STARFIELD),
        )
        assertTrue(encoded.contains("\"backgroundStyle\":\"STARFIELD\""))
        assertEquals(
            AssistantBackgroundStyle.STARFIELD,
            JsonInstant.decodeFromString<Assistant>(encoded).backgroundStyle,
        )
    }
}
