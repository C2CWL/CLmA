package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [Avatar] 被持久化在 DataStore（助手列表、用户头像）和备份里，判别字段 `type` 存的是
 * 旧的全限定类名。包名迁移后这些字符串必须仍能解码，否则助手列表整体解码失败并被回退为空列表。
 */
class AvatarSerialNameTest {

    private val dummyType = "me.rerere.rikkahub.data.model.Avatar.Dummy"
    private val emojiType = "me.rerere.rikkahub.data.model.Avatar.Emoji"
    private val imageType = "me.rerere.rikkahub.data.model.Avatar.Image"

    @Test
    fun `old format avatars decode`() {
        assertEquals(
            Avatar.Dummy,
            JsonInstant.decodeFromString(Avatar.serializer(), """{"type":"$dummyType"}"""),
        )
        assertEquals(
            Avatar.Emoji("😀"),
            JsonInstant.decodeFromString(Avatar.serializer(), """{"type":"$emojiType","content":"😀"}"""),
        )
        assertEquals(
            Avatar.Image("file:///data/user/0/x/files/upload/a.jpg"),
            JsonInstant.decodeFromString(
                Avatar.serializer(),
                """{"type":"$imageType","url":"file:///data/user/0/x/files/upload/a.jpg"}""",
            ),
        )
    }

    @Test
    fun `old format assistant list decodes with all avatar kinds`() {
        val stored = """
            [
              {"id":"0950e2dc-9bd5-4801-afa3-aa887aa36b4e","name":"A","avatar":{"type":"$dummyType"}},
              {"id":"3d47790c-c415-4b90-9388-751128adb0a0","name":"B","avatar":{"type":"$emojiType","content":"🐱"}},
              {"id":"b0a1c2d3-e4f5-4a6b-8c7d-9e0f1a2b3c4d","name":"C","avatar":{"type":"$imageType","url":"https://example.com/c.png"}}
            ]
        """.trimIndent()
        val assistants = JsonInstant.decodeFromString<List<Assistant>>(stored)
        assertEquals(3, assistants.size)
        assertEquals(Avatar.Dummy, assistants[0].avatar)
        assertEquals(Avatar.Emoji("🐱"), assistants[1].avatar)
        assertEquals(Avatar.Image("https://example.com/c.png"), assistants[2].avatar)
    }

    @Test
    fun `round trip keeps the old fully qualified type strings`() {
        val cases = listOf(
            Avatar.Dummy to dummyType,
            Avatar.Emoji("😀") to emojiType,
            Avatar.Image("https://example.com/a.png") to imageType,
        )
        for ((avatar, expectedType) in cases) {
            val encoded = JsonInstant.encodeToString(Avatar.serializer(), avatar)
            val type = JsonInstant.parseToJsonElement(encoded).jsonObject["type"]!!.jsonPrimitive.content
            assertEquals(expectedType, type)
            assertEquals(avatar, JsonInstant.decodeFromString(Avatar.serializer(), encoded))
        }
    }

    @Test
    fun `assistant round trip keeps the old avatar type string`() {
        val assistant = Assistant(name = "X", avatar = Avatar.Emoji("🦊"))
        val encoded = JsonInstant.encodeToString(Assistant.serializer(), assistant)
        val avatarJson = JsonInstant.parseToJsonElement(encoded).jsonObject["avatar"] as JsonObject
        assertEquals(emojiType, avatarJson["type"]!!.jsonPrimitive.content)
        assertEquals(assistant.avatar, JsonInstant.decodeFromString(Assistant.serializer(), encoded).avatar)
    }
}
