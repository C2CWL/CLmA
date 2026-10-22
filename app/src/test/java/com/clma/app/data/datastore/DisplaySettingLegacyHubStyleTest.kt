package com.clma.app.data.datastore

import com.clma.app.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The settings hub used to have a 「原版 / 新版」 switch stored as `settingsHubStyle`. The switch
 * is gone; settings saved by older versions must still load (the key is simply ignored).
 */
class DisplaySettingLegacyHubStyleTest {

    @Test
    fun `old blob with classic hub style still decodes`() {
        val decoded = JsonInstant.decodeFromString(
            DisplaySetting.serializer(),
            """{"showUserAvatar":false,"settingsHubStyle":"classic"}""",
        )
        assertEquals(false, decoded.showUserAvatar)
    }

    @Test
    fun `old blob with an unknown hub style value still decodes`() {
        val decoded = JsonInstant.decodeFromString(
            DisplaySetting.serializer(),
            """{"settingsHubStyle":"something-new","showUserAvatar":true}""",
        )
        assertEquals(true, decoded.showUserAvatar)
    }
}
