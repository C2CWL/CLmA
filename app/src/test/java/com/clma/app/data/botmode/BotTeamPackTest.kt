package com.clma.app.data.botmode

import com.clma.app.data.datastore.BotChannel
import com.clma.app.data.datastore.DisplaySetting
import com.clma.app.data.datastore.Settings
import com.clma.app.data.model.Assistant
import com.clma.app.data.model.AssistantLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BotTeamPackTest {
    @Test
    fun `import keeps bots and their channel in the same settings snapshot`() {
        val existing = Assistant(name = "已有助手")
        val channel = BotChannel(name = "已有频道")
        val settings =
            Settings(
                assistants = listOf(existing),
                assistantId = existing.id,
                displaySetting = DisplaySetting(botModeEnabled = true, botChannels = listOf(channel)),
            )
        val pack =
            parseBotTeamPackMarkdown(
                "---\nteam: 调研小队\n---\n# 团队备注\n- name: 研究员\n  system: 查资料\n- name: 写手",
            )

        val imported = settings.withImportedBotTeam(pack)
        val bots = imported.assistants.drop(1)
        val team = imported.displaySetting.botChannels.last()

        assertEquals(listOf("研究员", "写手"), bots.map { it.name })
        assertTrue(bots.all { it.library == AssistantLibrary.BOT })
        assertEquals("查资料", bots.first().systemPrompt)
        assertEquals("调研小队", team.name)
        assertEquals("# 团队备注", team.description)
        assertEquals(bots.map { it.id }, team.memberAssistantIds)
        assertEquals(
            settings.copy(
                assistants = settings.assistants + bots,
                displaySetting = settings.displaySetting.copy(botChannels = listOf(channel, team)),
            ),
            imported,
        )
    }

    @Test
    fun `unnamed import adds bots without creating a channel`() {
        val settings = Settings()
        val pack = BotTeamPackPreview(teamName = "", members = listOf(BotTeamMemberDraft("甲")))

        val imported = settings.withImportedBotTeam(pack)

        assertEquals(settings.assistants.size + 1, imported.assistants.size)
        assertEquals("甲", imported.assistants.last().name)
        assertEquals(settings.displaySetting, imported.displaySetting)
    }

    @Test
    fun `empty import preserves settings and creates no channel`() {
        val settings = Settings()
        val pack = BotTeamPackPreview(teamName = "空小队", members = emptyList())

        assertEquals(settings, settings.withImportedBotTeam(pack))
    }

    @Test
    fun `parses frontmatter and named members`() {
        val md = """
            ---
            team: 调研小队
            ---
            - name: 研究员
              system: 你负责查资料
            - name: 写手
              system: 你负责润色
        """.trimIndent()
        val pack = parseBotTeamPackMarkdown(md)
        assertEquals("调研小队", pack.teamName)
        assertEquals(2, pack.members.size)
        assertEquals("研究员", pack.members[0].name)
        assertTrue(pack.members[0].systemPrompt.contains("查资料"))
        assertEquals("写手", pack.members[1].name)
    }

    @Test
    fun `parses plain bullet names`() {
        val pack = parseBotTeamPackMarkdown("- 甲\n- 乙\n")
        assertEquals(2, pack.members.size)
        assertEquals("甲", pack.members[0].name)
        assertEquals("乙", pack.members[1].name)
    }

    @Test
    fun `empty input yields no members`() {
        val pack = parseBotTeamPackMarkdown("   ")
        assertTrue(pack.members.isEmpty())
    }
}
