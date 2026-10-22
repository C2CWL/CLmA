package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-02 — `disabledLocalTools` must not perturb a default assistant's persisted form.
 *
 * The field carries `@EncodeDefault(NEVER)`, so an empty set is omitted even though
 * [JsonInstant] sets `encodeDefaults = true`. That keeps the on-disk JSON of every existing
 * assistant byte-identical to what it was before this card — the "empty = no-op" red line,
 * enforced at the storage layer as well as at the tool-surface layer.
 */
class AssistantDisabledToolsSerializationTest {

    @Test
    fun `an empty deny list is not written to json`() {
        val json = JsonInstant.encodeToString(Assistant())
        assertFalse(json.contains("disabledLocalTools"))
    }

    @Test
    fun `a non-empty deny list round-trips`() {
        val encoded = JsonInstant.encodeToString(Assistant(disabledLocalTools = setOf("read_sensor")))
        assertTrue(encoded.contains("\"disabledLocalTools\":[\"read_sensor\"]"))
        assertEquals(
            setOf("read_sensor"),
            JsonInstant.decodeFromString<Assistant>(encoded).disabledLocalTools,
        )
    }
}
