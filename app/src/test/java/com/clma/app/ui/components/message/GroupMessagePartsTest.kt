package com.clma.app.ui.components.message

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import com.clma.ai.ui.UIMessagePart
import com.clma.app.data.ai.ContextCompactionPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [groupMessageParts] — the pure helper that splits a message's
 * part list into renderable blocks (consecutive Reasoning/Tool parts collapse
 * into a single ThinkingBlock; ask_user / request_secret become their own
 * InteractiveToolBlock; everything else becomes a ContentBlock).
 *
 * When [foldProcessNarration] is on, non-final Text between tool/reasoning
 * rounds becomes [ThinkingStep.NarrationStep] inside the same thinking block.
 */
class GroupMessagePartsTest {

    private fun text(s: String) = UIMessagePart.Text(s)
    private fun reasoning(s: String) = UIMessagePart.Reasoning(reasoning = s)
    private fun tool(id: String) = UIMessagePart.Tool(toolCallId = id, toolName = "t", input = "{}")
    private fun shell(id: String) = UIMessagePart.Tool(toolCallId = id, toolName = "workspace_shell", input = "{}")
    private fun compactionTool(id: String = "compact-1") = UIMessagePart.Tool(
        toolCallId = id,
        toolName = ContextCompactionPresentation.TOOL_NAME,
        input = "{}",
        metadata = buildJsonObject {
            put("rikkahub_display_only_context_compaction", true)
        },
    )

    @Test
    fun `empty list produces no blocks`() {
        assertTrue(emptyList<UIMessagePart>().groupMessageParts().isEmpty())
    }

    @Test
    fun `single text part becomes one content block preserving index`() {
        val result = listOf(text("hello")).groupMessageParts()
        assertEquals(1, result.size)
        val block = result[0] as MessagePartBlock.ContentBlock
        assertEquals(0, block.index)
        assertEquals(text("hello"), block.part)
    }

    @Test
    fun `consecutive reasoning and tool parts collapse into one thinking block`() {
        val result = listOf(
            reasoning("think a"),
            tool("call-1"),
            reasoning("think b"),
        ).groupMessageParts()

        assertEquals(1, result.size)
        val block = result[0] as MessagePartBlock.ThinkingBlock
        assertEquals(3, block.steps.size)
        assertTrue(block.steps[0] is ThinkingStep.ReasoningStep)
        assertTrue(block.steps[1] is ThinkingStep.ToolStep)
        assertTrue(block.steps[2] is ThinkingStep.ReasoningStep)
    }

    @Test
    fun `content part flushes pending thinking steps before it`() {
        val result = listOf(
            reasoning("think"),
            tool("call-1"),
            text("answer"),
        ).groupMessageParts()

        assertEquals(2, result.size)
        val thinking = result[0] as MessagePartBlock.ThinkingBlock
        assertEquals(2, thinking.steps.size)
        val content = result[1] as MessagePartBlock.ContentBlock
        assertEquals(text("answer"), content.part)
        // index is the original position in the source list
        assertEquals(2, content.index)
    }

    @Test
    fun `thinking blocks separated by content do not merge`() {
        val result = listOf(
            reasoning("first"),
            text("middle"),
            reasoning("second"),
        ).groupMessageParts()

        assertEquals(3, result.size)
        assertTrue(result[0] is MessagePartBlock.ThinkingBlock)
        assertTrue(result[1] is MessagePartBlock.ContentBlock)
        assertTrue(result[2] is MessagePartBlock.ThinkingBlock)
        assertEquals(1, (result[0] as MessagePartBlock.ThinkingBlock).steps.size)
        assertEquals(1, (result[2] as MessagePartBlock.ThinkingBlock).steps.size)
    }

    @Test
    fun `content block indices reflect original positions across mixed parts`() {
        val result = listOf(
            text("a"),       // index 0
            reasoning("r"),  // grouped
            text("b"),       // index 2
            text("c"),       // index 3
        ).groupMessageParts()

        assertEquals(4, result.size)
        assertEquals(0, (result[0] as MessagePartBlock.ContentBlock).index)
        assertTrue(result[1] is MessagePartBlock.ThinkingBlock)
        assertEquals(2, (result[2] as MessagePartBlock.ContentBlock).index)
        assertEquals(3, (result[3] as MessagePartBlock.ContentBlock).index)
    }

    @Test
    fun `trailing thinking steps are flushed at end of list`() {
        val result = listOf(
            text("intro"),
            reasoning("tail thought"),
        ).groupMessageParts()

        assertEquals(2, result.size)
        assertTrue(result[0] is MessagePartBlock.ContentBlock)
        assertTrue(result[1] is MessagePartBlock.ThinkingBlock)
    }

    @Test
    fun `blank text parts between tools do not split the step group`() {
        val result = listOf(
            tool("call-1"),
            tool("call-2"),
            text(""),
            tool("call-3"),
            tool("call-4"),
            text(" \n"),
            tool("call-5"),
        ).groupMessageParts()

        assertEquals(1, result.size)
        val block = result[0] as MessagePartBlock.ThinkingBlock
        assertEquals(5, block.steps.size)
        assertTrue(block.steps.all { it is ThinkingStep.ToolStep })
    }

    @Test
    fun `content after a skipped blank text keeps its original index`() {
        val result = listOf(
            tool("call-1"),
            text(""),
            tool("call-2"),
            text("answer"),
        ).groupMessageParts()

        assertEquals(2, result.size)
        assertEquals(2, (result[0] as MessagePartBlock.ThinkingBlock).steps.size)
        val content = result[1] as MessagePartBlock.ContentBlock
        assertEquals(text("answer"), content.part)
        assertEquals(3, content.index)
    }

    @Test
    fun `trailing blank text produces no content block`() {
        val result = listOf(reasoning("think"), tool("call-1"), text("")).groupMessageParts()

        assertEquals(1, result.size)
        assertTrue(result[0] is MessagePartBlock.ThinkingBlock)
    }

    @Test
    fun `blank text carrying a webview card is still rendered`() {
        val card = UIMessagePart.Text(
            text = "",
            metadata = buildJsonObject {
                putJsonObject("rikkahub.webview") { put("url", "https://example.com") }
            },
        )
        val result = listOf(tool("call-1"), card, tool("call-2")).groupMessageParts()

        assertEquals(3, result.size)
        assertEquals(card, (result[1] as MessagePartBlock.ContentBlock).part)
    }

    @Test
    fun `fold off keeps default split when narration sits between tools`() {
        val result = listOf(
            tool("a"),
            text("checking…"),
            tool("b"),
            text("final"),
        ).groupMessageParts(foldProcessNarration = false)

        assertEquals(4, result.size)
        assertTrue(result[0] is MessagePartBlock.ThinkingBlock)
        assertTrue(result[1] is MessagePartBlock.ContentBlock)
        assertTrue(result[2] is MessagePartBlock.ThinkingBlock)
        assertTrue(result[3] is MessagePartBlock.ContentBlock)
    }

    @Test
    fun `fold on merges process narration into one thinking block`() {
        val result = listOf(
            text("intro plan"),
            tool("a"),
            text("checking…"),
            reasoning("hmm"),
            tool("b"),
            text("final answer"),
        ).groupMessageParts(foldProcessNarration = true)

        assertEquals(2, result.size)
        val thinking = result[0] as MessagePartBlock.ThinkingBlock
        assertEquals(5, thinking.steps.size)
        assertTrue(thinking.steps[0] is ThinkingStep.NarrationStep)
        assertEquals(0, (thinking.steps[0] as ThinkingStep.NarrationStep).index)
        assertTrue(thinking.steps[1] is ThinkingStep.ToolStep)
        assertTrue(thinking.steps[2] is ThinkingStep.NarrationStep)
        assertEquals("checking…", (thinking.steps[2] as ThinkingStep.NarrationStep).text.text)
        assertTrue(thinking.steps[3] is ThinkingStep.ReasoningStep)
        assertTrue(thinking.steps[4] is ThinkingStep.ToolStep)
        val content = result[1] as MessagePartBlock.ContentBlock
        assertEquals(text("final answer"), content.part)
        assertEquals(5, content.index)
    }

    @Test
    fun `fold on leaves pure text messages as content`() {
        val result = listOf(text("hello"), text("world")).groupMessageParts(foldProcessNarration = true)
        assertEquals(2, result.size)
        assertTrue(result.all { it is MessagePartBlock.ContentBlock })
    }

    @Test
    fun `fold on still keeps webview cards as content even between tools`() {
        val card = UIMessagePart.Text(
            text = "open",
            metadata = buildJsonObject {
                putJsonObject("rikkahub.webview") { put("url", "https://example.com") }
            },
        )
        val result = listOf(tool("a"), card, tool("b"), text("done"))
            .groupMessageParts(foldProcessNarration = true)

        // card flushes, so two thinking blocks around the card content
        assertEquals(4, result.size)
        assertTrue(result[0] is MessagePartBlock.ThinkingBlock)
        assertEquals(card, (result[1] as MessagePartBlock.ContentBlock).part)
        assertTrue(result[2] is MessagePartBlock.ThinkingBlock)
        assertEquals(text("done"), (result[3] as MessagePartBlock.ContentBlock).part)
    }

    @Test
    fun `fold on keeps multiple trailing text parts as content`() {
        val result = listOf(
            tool("a"),
            text("answer part 1"),
            text("answer part 2"),
        ).groupMessageParts(foldProcessNarration = true)

        assertEquals(3, result.size)
        assertTrue(result[0] is MessagePartBlock.ThinkingBlock)
        assertEquals(text("answer part 1"), (result[1] as MessagePartBlock.ContentBlock).part)
        assertEquals(text("answer part 2"), (result[2] as MessagePartBlock.ContentBlock).part)
    }

    @Test
    fun `fold on keeps final answer as content when compaction tool is attached at tail`() {
        val result = listOf(
            tool("search"),
            text("Here is the answer"),
            compactionTool(),
        ).groupMessageParts(foldProcessNarration = true)

        assertEquals(3, result.size)
        val thinking = result[0] as MessagePartBlock.ThinkingBlock
        assertEquals(1, thinking.steps.size)
        assertTrue(thinking.steps[0] is ThinkingStep.ToolStep)

        val content = result[1] as MessagePartBlock.ContentBlock
        assertEquals(1, content.index)
        assertEquals(text("Here is the answer"), content.part)

        val compactionBlock = result[2] as MessagePartBlock.ThinkingBlock
        assertEquals(1, compactionBlock.steps.size)
        assertTrue(compactionBlock.steps[0] is ThinkingStep.ToolStep)
    }

    @Test
    fun `fold on pure text followed by compaction tool keeps text as content`() {
        val result = listOf(
            text("Direct response"),
            compactionTool(),
        ).groupMessageParts(foldProcessNarration = true)

        assertEquals(2, result.size)
        val content = result[0] as MessagePartBlock.ContentBlock
        assertEquals(0, content.index)
        assertEquals(text("Direct response"), content.part)

        val compactionBlock = result[1] as MessagePartBlock.ThinkingBlock
        assertEquals(1, compactionBlock.steps.size)
        assertTrue(compactionBlock.steps[0] is ThinkingStep.ToolStep)
    }

    @Test
    fun `fold on intermediate narration before real tool is still folded when compaction tool exists at tail`() {
        val result = listOf(
            text("checking"),
            tool("search"),
            text("final answer"),
            compactionTool(),
        ).groupMessageParts(foldProcessNarration = true)

        assertEquals(3, result.size)
        val thinking = result[0] as MessagePartBlock.ThinkingBlock
        assertEquals(2, thinking.steps.size)
        assertTrue(thinking.steps[0] is ThinkingStep.NarrationStep)
        assertEquals("checking", (thinking.steps[0] as ThinkingStep.NarrationStep).text.text)
        assertTrue(thinking.steps[1] is ThinkingStep.ToolStep)

        val content = result[1] as MessagePartBlock.ContentBlock
        assertEquals(2, content.index)
        assertEquals(text("final answer"), content.part)

        val compactionBlock = result[2] as MessagePartBlock.ThinkingBlock
        assertEquals(1, compactionBlock.steps.size)
        assertTrue(compactionBlock.steps[0] is ThinkingStep.ToolStep)
    }

    @Test
    fun `shell stays open while it is the latest step`() {
        val call = shell("s1")
        assertTrue(!shellInlineOutputShouldCollapse(listOf(reasoning("think"), call), call))
    }

    @Test
    fun `shell collapses once a later reasoning part exists`() {
        val call = shell("s1")
        val parts = listOf(call, reasoning(""))
        assertTrue(shellInlineOutputShouldCollapse(parts, call))
    }

    @Test
    fun `shell collapses once a later text part exists`() {
        val call = shell("s1")
        val parts = listOf(reasoning("think"), call, text("answer"))
        assertTrue(shellInlineOutputShouldCollapse(parts, call))
    }

    @Test
    fun `shell does not collapse for a later tool that is not reply text`() {
        val call = shell("s1")
        val parts = listOf(call, tool("other"))
        assertTrue(!shellInlineOutputShouldCollapse(parts, call))
    }

    @Test
    fun `non shell tools never auto collapse`() {
        val call = tool("t1")
        assertTrue(!shellInlineOutputShouldCollapse(listOf(call, text("answer")), call))
    }

    @Test
    fun `only the shell before the reply collapses when two shells are split by text`() {
        val first = shell("s1")
        val second = shell("s2")
        val parts = listOf(first, text("mid"), second)
        assertTrue(shellInlineOutputShouldCollapse(parts, first))
        assertTrue(!shellInlineOutputShouldCollapse(parts, second))
    }

    private fun ask(id: String) = UIMessagePart.Tool(toolCallId = id, toolName = "ask_user", input = "{}")
    private fun secret(id: String) = UIMessagePart.Tool(toolCallId = id, toolName = "request_secret", input = "{}")

    @Test
    fun `interactive ask_user flushes thinking and becomes its own block`() {
        val result = listOf(
            reasoning("think"),
            tool("call-1"),
            ask("q1"),
            text("after"),
        ).groupMessageParts()

        assertEquals(3, result.size)
        val thinking = result[0] as MessagePartBlock.ThinkingBlock
        assertEquals(2, thinking.steps.size)
        val card = result[1] as MessagePartBlock.InteractiveToolBlock
        assertEquals(2, card.index)
        assertEquals("ask_user", card.tool.toolName)
        assertEquals(text("after"), (result[2] as MessagePartBlock.ContentBlock).part)
    }

    @Test
    fun `request_secret is also an interactive block and splits surrounding tools`() {
        val result = listOf(tool("a"), secret("s1"), tool("b")).groupMessageParts()

        assertEquals(3, result.size)
        assertTrue(result[0] is MessagePartBlock.ThinkingBlock)
        val card = result[1] as MessagePartBlock.InteractiveToolBlock
        assertEquals(1, card.index)
        assertEquals("request_secret", card.tool.toolName)
        assertTrue(result[2] is MessagePartBlock.ThinkingBlock)
    }

    @Test
    fun `fold on does not swallow text before an interactive tool into the chain`() {
        val result = listOf(text("please answer"), ask("q")).groupMessageParts(foldProcessNarration = true)

        assertEquals(2, result.size)
        assertEquals(text("please answer"), (result[0] as MessagePartBlock.ContentBlock).part)
        assertTrue(result[1] is MessagePartBlock.InteractiveToolBlock)
    }

    @Test
    fun `fold on still folds narration before a real tool that precedes ask_user`() {
        val result = listOf(
            text("plan"),
            tool("a"),
            text("now I ask"),
            ask("q"),
        ).groupMessageParts(foldProcessNarration = true)

        assertEquals(3, result.size)
        val thinking = result[0] as MessagePartBlock.ThinkingBlock
        assertTrue(thinking.steps[0] is ThinkingStep.NarrationStep)
        assertTrue(thinking.steps[1] is ThinkingStep.ToolStep)
        assertEquals(text("now I ask"), (result[1] as MessagePartBlock.ContentBlock).part)
        assertTrue(result[2] is MessagePartBlock.InteractiveToolBlock)
    }

    @Test
    fun `shell collapses once a later interactive tool starts`() {
        val call = shell("s1")
        assertTrue(shellInlineOutputShouldCollapse(listOf(call, ask("q")), call))
        assertTrue(shellInlineOutputShouldCollapse(listOf(call, secret("s")), call))
    }

    @Test
    fun `two interactive tools stay separate blocks`() {
        val result = listOf(ask("q1"), secret("s1")).groupMessageParts()
        assertEquals(2, result.size)
        assertTrue(result[0] is MessagePartBlock.InteractiveToolBlock)
        assertTrue(result[1] is MessagePartBlock.InteractiveToolBlock)
        assertEquals(0, (result[0] as MessagePartBlock.InteractiveToolBlock).index)
        assertEquals(1, (result[1] as MessagePartBlock.InteractiveToolBlock).index)
    }
}