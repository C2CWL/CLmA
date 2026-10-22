package me.rerere.rikkahub.data.agentdef

import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P2-06 — the TEXT encodings behind [AgentDefinition]'s structured columns.
 *
 * Two properties matter more than the round-trip itself:
 *
 *  - **null must stay null.** Null means "inherit the parent", so a converter that turned a
 *    null column into the literal string `null` and back would silently pin the expert to an
 *    empty surface instead of inheriting — a behaviour change with no visible cause.
 *  - **an unknown tool type must not sink the row.** The column is written by whichever build
 *    knows the most tool types; an older build must drop the entries it cannot name and keep
 *    the rest, because a definition that cannot be read at all is worse than one that lost a
 *    single toggle.
 *
 * Exercises only the pure converters, so it runs on a plain JVM without Room.
 */
class AgentDefinitionConvertersTest {

    private val converters = AgentDefinitionConverters()

    @Test
    fun `a null local tool list round-trips as null`() {
        assertNull(converters.localToolsToJson(null))
        assertNull(converters.jsonToLocalTools(null))
    }

    @Test
    fun `an empty local tool list round-trips as empty, not null`() {
        val encoded = converters.localToolsToJson(emptyList())
        assertEquals("[]", encoded)
        assertEquals(emptyList<LocalToolOption>(), converters.jsonToLocalTools(encoded))
    }

    @Test
    fun `a local tool list round-trips preserving order`() {
        val value = listOf(LocalToolOption.TimeInfo, LocalToolOption.SubAgents, LocalToolOption.Browser)
        val decoded = converters.jsonToLocalTools(converters.localToolsToJson(value))
        assertEquals(value, decoded)
    }

    @Test
    fun `a tool type this build does not know is dropped, the rest survive`() {
        val decoded = converters.jsonToLocalTools("""[{"type":"time_info"},{"type":"bogus"},{"type":"browser"}]""")
        assertEquals(listOf(LocalToolOption.TimeInfo, LocalToolOption.Browser), decoded)
    }

    @Test
    fun `a null string set round-trips as null and an empty one as empty`() {
        assertNull(converters.stringSetToJson(null))
        assertNull(converters.jsonToStringSet(null))
        assertEquals("[]", converters.stringSetToJson(emptySet()))
        assertEquals(emptySet<String>(), converters.jsonToStringSet("[]"))
    }

    @Test
    fun `a string set round-trips`() {
        val value = setOf("read_sensor", "take_photo")
        assertEquals(value, converters.jsonToStringSet(converters.stringSetToJson(value)))
    }

    @Test
    fun `the encoding matches how Assistant stores the same tool list`() {
        // The whole point of reusing LenientLocalToolListSerializer: the two encodings cannot
        // drift, so a tool list can be moved between an assistant and a definition verbatim.
        // `time_info` is the real @SerialName of LocalToolOption.TimeInfo.
        val value = listOf(LocalToolOption.TimeInfo)
        assertEquals("""[{"type":"time_info"}]""", converters.localToolsToJson(value))
    }
}
