package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The drawer's capsule chrome is on by default: no cards, controls sit directly on the frosted
 * panel. Settings stored before the field existed must read back as on, and switching it off must
 * round-trip so the two floating cards come back.
 */
class DisplaySettingDrawerCapsulesTest {

    @Test
    fun `drawer capsules are on by default`() {
        assertTrue(DisplaySetting().drawerCapsules)
    }

    @Test
    fun `stored settings without the key read back as on`() {
        val legacy = """{"cleanLayout":true}"""
        val decoded = JsonInstant.decodeFromString<DisplaySetting>(legacy)
        assertTrue(decoded.drawerCapsules)
    }

    @Test
    fun `turning it off round-trips`() {
        val json = JsonInstant.encodeToString(DisplaySetting(drawerCapsules = false))
        assertTrue(json.contains("\"drawerCapsules\":false"))
        assertFalse(JsonInstant.decodeFromString<DisplaySetting>(json).drawerCapsules)
    }
}