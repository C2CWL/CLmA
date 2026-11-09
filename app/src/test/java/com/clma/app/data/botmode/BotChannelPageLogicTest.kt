package com.clma.app.data.botmode

import com.clma.app.data.datastore.BotChannel
import com.clma.app.data.datastore.DisplaySetting
import com.clma.app.data.datastore.Settings
import com.clma.app.data.model.Assistant
import com.clma.app.data.model.AssistantLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class BotChannelPageLogicTest {
    private val classic = Assistant(name = "经典")
    private val a = Assistant(name = "研究员", library = AssistantLibrary.BOT)
    private val b = Assistant(name = "写手", library = AssistantLibrary.BOT)

    @Test
    fun `channel members come from the bot library only`() {
        val channel = BotChannel(name = "c", memberAssistantIds = listOf(classic.id, a.id, b.id, Uuid.random()))
        val s = Settings(assistants = listOf(classic, a, b), displaySetting = DisplaySetting(botChannels = listOf(channel)))
        assertEquals(listOf(a, b), channel.botMembers(s))
    }

    @Test
    fun `targets are the mentioned members or everyone`() {
        assertEquals(listOf(b.id), channelTargets("请 @写手 润色", listOf(a, b)))
        assertEquals(listOf(a.id, b.id), channelTargets("大家好", listOf(a, b)))
        assertTrue(channelTargets("hi", emptyList()).isEmpty())
    }

    @Test
    fun `members get the channel context and the text unchanged`() {
        val channel = BotChannel(name = "小队", description = "一起写周报")
        val text = channelMessageText(channel, listOf("研究员", "写手"), "  @写手 先写初稿 ")
        assertTrue(text.startsWith("[Channel: 小队] Members: 研究员, 写手\n"))
        assertTrue(text.contains("Channel note: 一起写周报\n"))
        assertTrue(text.endsWith("\n\n@写手 先写初稿"))
    }

    @Test
    fun `team pack export reads back with prompts intact`() {
        val prompt = """
            # 角色
            - 先列要点
            name: 不是成员
            
              缩进行
        """.trimIndent()
        val bots = listOf(
            Assistant(name = "研究员", systemPrompt = prompt, library = AssistantLibrary.BOT),
            Assistant(name = "写手", systemPrompt = "你负责润色", library = AssistantLibrary.BOT),
        )
        val md = toTeamPackMarkdown("调研小队", bots)
        val pack = parseBotTeamPackMarkdown(md)
        assertEquals("调研小队", pack.teamName)
        assertEquals(listOf("研究员", "写手"), pack.members.map { it.name })
        assertEquals(prompt, pack.members[0].systemPrompt)
        assertEquals("你负责润色", pack.members[1].systemPrompt)
        assertTrue(pack.members.all { it.toAssistant().library == AssistantLibrary.BOT })
    }
}
