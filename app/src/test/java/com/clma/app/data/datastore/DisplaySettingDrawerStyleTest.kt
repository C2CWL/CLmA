package com.clma.app.data.datastore

import com.clma.app.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 侧边栏样式默认是「助手卡片」，旧设置读回也是它；切到「助手导轨」能存能读。 */
class DisplaySettingDrawerStyleTest {

    @Test
    fun `drawer style defaults to card`() {
        assertEquals(DrawerStyle.CARD, DisplaySetting().drawerStyle)
    }

    @Test
    fun `stored settings without the key read back as card`() {
        val decoded = JsonInstant.decodeFromString<DisplaySetting>("""{"cleanLayout":true}""")
        assertEquals(DrawerStyle.CARD, decoded.drawerStyle)
    }

    @Test
    fun `rail round-trips`() {
        val json = JsonInstant.encodeToString(DisplaySetting(drawerStyle = DrawerStyle.RAIL))
        assertTrue(json.contains("\"drawerStyle\":\"rail\""))
        assertEquals(DrawerStyle.RAIL, JsonInstant.decodeFromString<DisplaySetting>(json).drawerStyle)
    }
}
