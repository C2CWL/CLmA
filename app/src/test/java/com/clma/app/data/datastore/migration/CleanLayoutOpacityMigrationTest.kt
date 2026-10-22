package com.clma.app.data.datastore.migration

import com.clma.app.data.datastore.DisplaySetting
import com.clma.app.utils.JsonInstant
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** V6: an untouched 1.0 panel strength moves to the new 0.4 default; anything else is kept. */
class CleanLayoutOpacityMigrationTest {

    private fun decode(json: String) = JsonInstant.decodeFromString<DisplaySetting>(json)

    @Test
    fun `old default 1 becomes the new default for both panels`() {
        val out = decode(
            migrateCleanLayoutOpacityJson("""{"cleanLayout":true,"cleanLayoutTopOpacity":1.0,"cleanLayoutBottomOpacity":1.0}""")
        )
        assertEquals(0.4f, out.cleanLayoutTopOpacity, 0f)
        assertEquals(0.4f, out.cleanLayoutBottomOpacity, 0f)
        assertEquals(true, out.cleanLayout)
    }

    @Test
    fun `integer 1 is treated like 1 point 0`() {
        val out = decode(migrateCleanLayoutOpacityJson("""{"cleanLayoutTopOpacity":1,"cleanLayoutBottomOpacity":1}"""))
        assertEquals(0.4f, out.cleanLayoutTopOpacity, 0f)
        assertEquals(0.4f, out.cleanLayoutBottomOpacity, 0f)
    }

    @Test
    fun `values the user picked are preserved`() {
        val input = """{"cleanLayoutTopOpacity":0.62,"cleanLayoutBottomOpacity":0.33}"""
        val migrated = migrateCleanLayoutOpacityJson(input)
        assertSame(input, migrated)
        val out = decode(migrated)
        assertEquals(0.62f, out.cleanLayoutTopOpacity, 0f)
        assertEquals(0.33f, out.cleanLayoutBottomOpacity, 0f)
    }

    @Test
    fun `only the untouched panel is rewritten`() {
        val out = decode(migrateCleanLayoutOpacityJson("""{"cleanLayoutTopOpacity":0.62,"cleanLayoutBottomOpacity":1.0}"""))
        assertEquals(0.62f, out.cleanLayoutTopOpacity, 0f)
        assertEquals(0.4f, out.cleanLayoutBottomOpacity, 0f)
    }

    @Test
    fun `zero stays zero`() {
        val out = decode(migrateCleanLayoutOpacityJson("""{"cleanLayoutTopOpacity":0.0,"cleanLayoutBottomOpacity":0}"""))
        assertEquals(0f, out.cleanLayoutTopOpacity, 0f)
        assertEquals(0f, out.cleanLayoutBottomOpacity, 0f)
    }

    @Test
    fun `other fields are untouched`() {
        val migrated = migrateCleanLayoutOpacityJson(
            """{"cleanLayoutTopOpacity":1.0,"cleanLayoutTopHeight":43.0,"userNickname":"小明","unknownKey":[1,2]}"""
        )
        val obj = JsonInstant.parseToJsonElement(migrated).jsonObject
        assertEquals("43.0", obj["cleanLayoutTopHeight"]!!.jsonPrimitive.content)
        assertEquals("小明", obj["userNickname"]!!.jsonPrimitive.content)
        assertEquals("[1,2]", obj["unknownKey"].toString())
        assertEquals(0.4f, obj["cleanLayoutTopOpacity"]!!.jsonPrimitive.content.toFloat(), 0f)
    }

    @Test
    fun `missing keys, strings and broken json are left alone`() {
        val missing = """{"cleanLayout":true}"""
        assertSame(missing, migrateCleanLayoutOpacityJson(missing))
        val quoted = """{"cleanLayoutTopOpacity":"1.0"}"""
        assertSame(quoted, migrateCleanLayoutOpacityJson(quoted))
        val broken = "{not json"
        assertSame(broken, migrateCleanLayoutOpacityJson(broken))
        val array = "[1,2]"
        assertSame(array, migrateCleanLayoutOpacityJson(array))
    }

    @Test
    fun `a full serialized setting with the old default migrates`() {
        val old = JsonInstant.encodeToString(DisplaySetting(cleanLayoutTopOpacity = 1f, cleanLayoutBottomOpacity = 1f))
        val out = decode(migrateCleanLayoutOpacityJson(old))
        assertEquals(0.4f, out.cleanLayoutTopOpacity, 0f)
        assertEquals(0.4f, out.cleanLayoutBottomOpacity, 0f)
    }
}
