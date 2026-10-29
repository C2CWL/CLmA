package com.clma.app.data.datastore.migration

import com.clma.app.data.datastore.DisplaySetting
import com.clma.app.utils.JsonInstant
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * V9: the stored `true` that every install carries as a default is rewritten to `false` once,
 * so the avatar is gone from the chat and the drawer without a settings tap. Any other field is
 * left exactly as it was.
 */
class UserAvatarHiddenMigrationTest {

    private fun decode(json: String) = JsonInstant.decodeFromString<DisplaySetting>(json)

    @Test
    fun `stored true is rewritten to false`() {
        val out = decode(migrateUserAvatarHiddenJson("""{"showUserAvatar":true}"""))
        assertFalse(out.showUserAvatar)
    }

    @Test
    fun `already false is left untouched`() {
        val input = """{"showUserAvatar":false}"""
        assertSame(input, migrateUserAvatarHiddenJson(input))
    }

    @Test
    fun `a missing key is left untouched`() {
        val input = """{"userNickname":"小明"}"""
        assertSame(input, migrateUserAvatarHiddenJson(input))
    }

    @Test
    fun `other fields survive the rewrite`() {
        val migrated = migrateUserAvatarHiddenJson(
            """{"showUserAvatar":true,"userNickname":"小明","cleanLayout":true,"unknownKey":[1,2]}"""
        )
        val obj = JsonInstant.parseToJsonElement(migrated).jsonObject
        assertEquals("false", obj["showUserAvatar"]!!.jsonPrimitive.content)
        assertEquals("小明", obj["userNickname"]!!.jsonPrimitive.content)
        assertEquals("true", obj["cleanLayout"]!!.jsonPrimitive.content)
        assertEquals("[1,2]", obj["unknownKey"].toString())
    }

    @Test
    fun `broken json, arrays and string values are left alone`() {
        val broken = "{not json"
        assertSame(broken, migrateUserAvatarHiddenJson(broken))
        val array = "[1,2]"
        assertSame(array, migrateUserAvatarHiddenJson(array))
        val quoted = """{"showUserAvatar":"true"}"""
        assertSame(quoted, migrateUserAvatarHiddenJson(quoted))
    }

    @Test
    fun `a full serialized setting with the old default migrates`() {
        val old = JsonInstant.encodeToString(DisplaySetting(showUserAvatar = true))
        assertFalse(decode(migrateUserAvatarHiddenJson(old)).showUserAvatar)
    }
}
