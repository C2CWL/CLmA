package com.clma.app.data.datastore.migration

import com.clma.app.data.datastore.DisplaySetting
import com.clma.app.data.datastore.StreamingPace
import com.clma.app.utils.JsonInstant
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * V12: the stored boolean switch becomes a three-way pace. `true` (the default, publish every
 * token) maps to immediate and `false` (the 32 ms batch) maps to balanced, so nobody's choice
 * changes meaning. Any other field is left exactly as it was.
 */
class StreamingPaceMigrationTest {

    private fun decode(json: String) = JsonInstant.decodeFromString<DisplaySetting>(json)

    @Test
    fun `stored true becomes immediate`() {
        val out = decode(migrateStreamingPaceJson("""{"fastStreamingOutput":true}"""))
        assertEquals(StreamingPace.IMMEDIATE, out.streamingPace)
    }

    @Test
    fun `stored false becomes balanced`() {
        val out = decode(migrateStreamingPaceJson("""{"fastStreamingOutput":false}"""))
        assertEquals(StreamingPace.BALANCED, out.streamingPace)
    }

    @Test
    fun `a missing key is left untouched`() {
        val input = """{"userNickname":"小明"}"""
        assertSame(input, migrateStreamingPaceJson(input))
    }

    @Test
    fun `other fields survive and the old key is gone`() {
        val migrated = migrateStreamingPaceJson(
            """{"fastStreamingOutput":false,"userNickname":"小明","cleanLayout":true,"unknownKey":[1,2]}"""
        )
        val obj = JsonInstant.parseToJsonElement(migrated).jsonObject
        assertEquals("balanced", obj["streamingPace"]!!.jsonPrimitive.content)
        assertNull(obj["fastStreamingOutput"])
        assertEquals("小明", obj["userNickname"]!!.jsonPrimitive.content)
        assertEquals("true", obj["cleanLayout"]!!.jsonPrimitive.content)
        assertEquals("[1,2]", obj["unknownKey"].toString())
    }

    @Test
    fun `broken json, arrays and string values are left alone`() {
        val broken = "{not json"
        assertSame(broken, migrateStreamingPaceJson(broken))
        val array = "[1,2]"
        assertSame(array, migrateStreamingPaceJson(array))
        val quoted = """{"fastStreamingOutput":"true"}"""
        assertSame(quoted, migrateStreamingPaceJson(quoted))
    }

    @Test
    fun `a stored setting from the old version migrates`() {
        // What an older build wrote: the boolean field, plus its neighbours.
        val old = """{"fastStreamingOutput":true,"userNickname":"","bubbleOpacity":1.0}"""
        val out = decode(migrateStreamingPaceJson(old))
        assertEquals(StreamingPace.IMMEDIATE, out.streamingPace)
        assertEquals(1.0f, out.bubbleOpacity, 0f)
    }
}
