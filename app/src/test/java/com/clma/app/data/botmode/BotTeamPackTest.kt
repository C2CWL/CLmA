package com.clma.app.data.botmode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BotTeamPackTest {
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
