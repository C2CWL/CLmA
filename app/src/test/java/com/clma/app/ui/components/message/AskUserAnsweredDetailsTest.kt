package com.clma.app.ui.components.message

import com.clma.ai.ui.ToolApprovalState
import com.clma.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AskUserAnsweredDetailsTest {
    private fun askUserTool(
        input: String,
        approvalState: ToolApprovalState = ToolApprovalState.Auto,
    ) = UIMessagePart.Tool(
        toolCallId = "call-1",
        toolName = "ask_user",
        input = input,
        approvalState = approvalState,
    )

    @Test
    fun `answered details keep every original question with its answer`() {
        val input = """
            {"questions":[
              {"id":"q1","question":"用哪种数据库？","options":["SQLite","Room"],"selection_type":"single"},
              {"id":"q2","question":"是否保留离线模式？","options":[],"selection_type":"text"}
            ]}
        """.trimIndent()
        val answer = """{"answers":{"q1":"Room","q2":"是"}}"""
        val tool = askUserTool(input, ToolApprovalState.Answered(answer))

        val details = askUserAnsweredDetails(tool)

        assertEquals(2, details.size)
        assertEquals("用哪种数据库？", details[0].question)
        assertEquals("Room", details[0].answer)
        assertEquals("是否保留离线模式？", details[1].question)
        assertEquals("是", details[1].answer)
    }

    @Test
    fun `answered details fall back to raw answer without losing the question`() {
        val input = """{"questions":[{"id":"q1","question":"选哪个？","options":["A"]}]}"""
        val tool = askUserTool(input, ToolApprovalState.Answered("A"))

        val details = askUserAnsweredDetails(tool)

        assertEquals(1, details.size)
        assertEquals("选哪个？", details[0].question)
        assertEquals("A", details[0].answer)
    }

    @Test
    fun `raw fallback with multiple questions keeps questions and answer summary`() {
        val input = """
            {"questions":[
              {"id":"q1","question":"用哪种数据库？","options":["SQLite"]},
              {"id":"q2","question":"是否保留离线模式？","options":[]}
            ]}
        """.trimIndent()
        val tool = askUserTool(input, ToolApprovalState.Answered("A"))

        val details = askUserAnsweredDetails(tool)

        assertEquals(3, details.size)
        assertEquals("用哪种数据库？", details[0].question)
        assertEquals("是否保留离线模式？", details[1].question)
        assertEquals("", details[2].question)
        assertEquals("A", details[2].answer)
    }

    @Test
    fun `pending tool has no answered details`() {
        val input = """{"questions":[{"id":"q1","question":"选哪个？","options":["A"]}]}"""
        assertTrue(askUserAnsweredDetails(askUserTool(input)).isEmpty())
    }
}
