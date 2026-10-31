package com.clma.app.data.ai

import com.clma.ai.core.MessageRole
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextCompactionPlannerTest {
    @Test
    fun `partition sources keeps every character within the input budget`() {
        val source = "alpha ".repeat(120) + "\n" + "beta ".repeat(120)
        val budget = 100

        val groups = ContextCompactionPlanner.partitionSources(listOf(source), budget)

        assertTrue(groups.size > 1)
        assertTrue(groups.all { group ->
            ContextCompactionPlanner.estimateTokens(group.joinToString("\n\n")) <= budget
        })
        assertEquals(
            source.filterNot(Char::isWhitespace),
            groups.flatten().joinToString("").filterNot(Char::isWhitespace),
        )
    }

    @Test
    fun `input budget reserves room for prompt and summary`() {
        val budget = ContextCompactionPlanner.inputBudgetTokens(
            contextLength = 8_192,
            targetTokens = 2_000,
        )

        assertTrue(budget >= 512)
        assertTrue(budget <= 6_144)
        assertTrue(budget + 2_000 + 768 <= 8_192)
    }

    @Test
    fun `large compression contexts use 100k map chunks`() {
        val modelInputBudget = ContextCompactionPlanner.inputBudgetTokens(
            contextLength = 400_000,
            targetTokens = 2_000,
        )

        assertEquals(100_000, ContextCompactionPlanner.mapInputBudgetTokens(modelInputBudget))
    }

    @Test
    fun `explicit token ceiling allows one large compression group`() {
        val modelInputBudget = ContextCompactionPlanner.inputBudgetTokens(
            contextLength = 372_000,
            targetTokens = 2_000,
            allowFullContext = true,
        )

        assertTrue(modelInputBudget > 250_000)
        assertEquals(
            modelInputBudget,
            ContextCompactionPlanner.mapInputBudgetTokens(
                modelInputBudget,
                allowLargeContext = true,
            ),
        )
    }

    @Test
    fun `large CJK source is split into safe map chunks`() {
        val source = "文".repeat(400_000)

        val groups = ContextCompactionPlanner.partitionSources(
            sources = listOf(source),
            maxInputTokens = 100_000,
        )

        assertEquals(4, groups.size)
        assertTrue(groups.all { group ->
            ContextCompactionPlanner.estimateTokens(group.joinToString("\n\n")) <= 100_000
        })
        assertEquals(source, groups.flatten().joinToString(""))
    }

    @Test
    fun `source smaller than map budget stays in one request`() {
        val source = "a".repeat(300_000)

        val groups = ContextCompactionPlanner.partitionSources(
            sources = listOf(source),
            maxInputTokens = 100_000,
        )

        assertEquals(1, groups.size)
        assertEquals(source, groups.single().single())
    }

    @Test
    fun `map output target is distributed across groups and capped per request`() {
        assertEquals(
            15_000,
            ContextCompactionPlanner.mapOutputTargetTokens(
                finalTargetTokens = 30_000,
                groupCount = 2,
                maxPerRequestTokens = 16_384,
            ),
        )
        assertEquals(
            16_384,
            ContextCompactionPlanner.mapOutputTargetTokens(
                finalTargetTokens = 100_000,
                groupCount = 2,
                maxPerRequestTokens = 16_384,
            ),
        )
    }

    @Test
    fun `source text retains executed tool input and output`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "read_file",
                    input = "{\"path\":\"notes.txt\"}",
                    output = listOf(UIMessagePart.Text("important file content")),
                ),
            ),
        )

        val source = ContextCompactionPlanner.sourceText(message)

        assertTrue(source.contains("read_file"))
        assertTrue(source.contains("notes.txt"))
        assertTrue(source.contains("important file content"))
        assertTrue(source.contains("must be retained in summary"))
    }

    @Test
    fun `source text retains every tool record in one assistant response`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                executedTool("tool-b", "input-b", "result-b"),
                executedTool("tool-c", "input-c", "result-c"),
                executedTool("tool-d", "input-d", "result-d"),
            ),
        )

        val source = ContextCompactionPlanner.sourceText(message)

        listOf("tool-b", "input-b", "result-b", "tool-c", "input-c",
            "result-c", "tool-d", "input-d", "result-d").forEach { evidence ->
            assertTrue("Missing tool evidence: $evidence", source.contains(evidence))
        }
    }

    @Test
    fun `map source bounds huge tool output while retaining tool identity`() {
        val output = "HEAD-" + "x".repeat(8_000) + "-TAIL"
        val source = ContextCompactionPlanner.mapSourceText(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(executedTool("large-tool", "{\"id\":1}", output)),
            )
        )

        assertTrue(source.contains("large-tool"))
        assertTrue(source.contains("HEAD-"))
        assertTrue(source.contains("-TAIL"))
        assertTrue(source.contains("preview truncated for map request"))
        assertTrue(source.length < 2_000)
    }

    @Test
    fun `required retention instruction demands concrete tool outcomes`() {
        val instruction = ContextCompactionPlanner.requiredToolRetentionInstructions()

        assertTrue(instruction.contains("tool name"))
        assertTrue(instruction.contains("factual outcome"))
        assertTrue(instruction.contains("file paths"))
    }

    @Test
    fun `mandatory tool digest retains each completed tool result`() {
        val messages = listOf(
            UIMessage.user("Task A"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    executedTool("tool-b", "input-b", "result-b"),
                    executedTool("tool-c", "input-c", "result-c"),
                    executedTool("tool-d", "input-d", "result-d"),
                ),
            ),
        )

        val digest = ContextCompactionPlanner.mandatoryToolExecutionDigest(
            messages = messages,
            maxTokens = 1_000,
        )

        listOf("tool-b", "result-b", "tool-c", "result-c", "tool-d", "result-d")
            .forEach { evidence ->
                assertTrue("Missing digest evidence: $evidence", digest.contains(evidence))
            }
    }

    @Test
    fun `mandatory tool digest carries prior retained tools into subsequent compaction`() {
        val firstDigest = ContextCompactionPlanner.mandatoryToolExecutionDigest(
            messages = listOf(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(executedTool("tool-b", "input-b", "result-b")),
                ),
            ),
            maxTokens = 1_000,
        )
        val secondDigest = ContextCompactionPlanner.mandatoryToolExecutionDigest(
            messages = listOf(
                UIMessage.user("[Summary of previous conversation]\n\n$firstDigest"),
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(executedTool("tool-c", "input-c", "result-c")),
                ),
            ),
            maxTokens = 1_000,
        )

        listOf("tool-b", "result-b", "tool-c", "result-c").forEach { evidence ->
            assertTrue("Missing retained tool evidence: $evidence", secondDigest.contains(evidence))
        }
    }

    @Test
    fun `raw retention report exposes all 55 completed calls kept after summary`() {
        val rawTail = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = (1..55).map { index ->
                    executedTool("tool-$index", "input-$index", "result-$index")
                },
            )
        )

        val report = ContextCompactionPlanner.rawContextRetentionReport(rawTail)

        assertTrue(report.contains("completed_tool_calls=55"))
        assertTrue(report.contains("were not deleted or replaced"))
        assertEquals(55, ContextCompactionPlanner.retainedRawToolCallCount(report))
    }

    @Test
    fun `raw retention count only reads a delimited current report`() {
        val summary = """
            The model guessed completed_tool_calls=999 outside the report.
            [Raw context retained verbatim after this summary]
            raw_messages=1
            completed_tool_calls=55
            [End raw context retention report]
        """.trimIndent()

        assertEquals(55, ContextCompactionPlanner.retainedRawToolCallCount(summary))
        assertEquals(
            0,
            ContextCompactionPlanner.retainedRawToolCallCount("completed_tool_calls=999"),
        )
    }

    @Test
    fun `a later compaction source drops the previous raw-boundary report`() {
        val previousSummary = UIMessage.user(
            """
                Research facts that remain relevant.

                [Raw context retained verbatim after this summary]
                raw_messages=1
                completed_tool_calls=55
                These messages follow this summary in the active model context.
                [End raw context retention report]
            """.trimIndent()
        )

        val source = ContextCompactionPlanner.sourceText(previousSummary)

        assertTrue(source.contains("Research facts that remain relevant."))
        assertFalse(source.contains("completed_tool_calls=55"))
        assertFalse(source.contains("Raw context retained verbatim"))
    }

    @Test
    fun `55-call digest preserves every call identity and result`() {
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = (1..55).map { index ->
                    executedTool("tool-$index", "input-$index", "result-$index")
                },
            )
        )

        val digest = ContextCompactionPlanner.mandatoryToolExecutionDigest(
            messages = messages,
            maxTokens = 12_000,
        )

        (1..55).forEach { index ->
            assertTrue("Missing call id $index", digest.contains("call-tool-$index"))
            assertTrue("Missing result $index", digest.contains("result-$index"))
        }
    }

    @Test
    fun `automatic tail keeps the requested recent tool calls`() {
        val messages = listOf(
            UIMessage.user("old context"),
            toolMessage("first"),
            toolMessage("second"),
            UIMessage.user("follow up"),
            toolMessage("third"),
        )

        assertEquals(
            2,
            ContextCompactionPlanner.automaticTailStartIndex(
                messages = messages,
                rawTailStartIndex = 0,
                keepRecentToolCalls = 2,
            ),
        )
        assertEquals(
            messages.size,
            ContextCompactionPlanner.automaticTailStartIndex(
                messages = messages,
                rawTailStartIndex = 0,
                keepRecentToolCalls = 0,
            ),
        )
    }

    @Test
    fun `automatic tail falls back to full compaction when all raw messages must be retained`() {
        val messages = listOf(UIMessage.user("old context"), toolMessage("only tool"))

        assertEquals(
            messages.size,
            ContextCompactionPlanner.automaticTailStartIndex(
                messages = messages,
                rawTailStartIndex = 1,
                keepRecentToolCalls = 1,
            ),
        )
    }

    @Test
    fun `automatic tail within the token budget is returned unchanged`() {
        val messages = listOf(
            UIMessage.user("old context"),
            toolMessage("first"),
            toolMessage("second"),
            UIMessage.user("follow up"),
            toolMessage("third"),
        )
        val baseline = ContextCompactionPlanner.automaticTailStartIndex(
            messages = messages,
            rawTailStartIndex = 0,
            keepRecentToolCalls = 2,
        )
        val tailTokens = ContextBudgetPlanner.estimateContextTokens(
            messages.subList(baseline, messages.size),
        )

        assertEquals(
            baseline,
            ContextCompactionPlanner.automaticTailStartIndex(
                messages = messages,
                rawTailStartIndex = 0,
                keepRecentToolCalls = 2,
                maxTailTokens = tailTokens,
            ),
        )
    }

    @Test
    fun `automatic tail over the token budget advances the boundary until it fits`() {
        val messages = listOf(
            UIMessage.user("old context"),
            toolMessage("first"),
            toolMessage("second"),
            UIMessage.user("follow up"),
            toolMessage("third"),
        )
        val baseline = ContextCompactionPlanner.automaticTailStartIndex(
            messages = messages,
            rawTailStartIndex = 0,
            keepRecentToolCalls = 2,
        )
        val tailTokens = ContextBudgetPlanner.estimateContextTokens(
            messages.subList(baseline, messages.size),
        )

        val widened = ContextCompactionPlanner.automaticTailStartIndex(
            messages = messages,
            rawTailStartIndex = 0,
            keepRecentToolCalls = 2,
            maxTailTokens = tailTokens - 1,
        )

        assertTrue(widened > baseline)
        assertTrue(
            widened == messages.size ||
                ContextBudgetPlanner.estimateContextTokens(messages.subList(widened, messages.size)) <=
                tailTokens - 1,
        )
    }

    @Test
    fun `automatic tail with maxTailTokens null reproduces the current result exactly`() {
        val messages = listOf(
            UIMessage.user("old context"),
            toolMessage("first"),
            toolMessage("second"),
            UIMessage.user("follow up"),
            toolMessage("third"),
        )

        assertEquals(
            ContextCompactionPlanner.automaticTailStartIndex(
                messages = messages,
                rawTailStartIndex = 0,
                keepRecentToolCalls = 2,
            ),
            ContextCompactionPlanner.automaticTailStartIndex(
                messages = messages,
                rawTailStartIndex = 0,
                keepRecentToolCalls = 2,
                maxTailTokens = null,
            ),
        )
    }

    @Test
    fun `automatic tail never regresses to or below rawTailStartIndex under a tiny budget`() {
        val messages = listOf(
            UIMessage.user("old"),
            toolMessage("first"),
            toolMessage("second"),
            UIMessage.user("follow up"),
            toolMessage("third"),
            toolMessage("fourth"),
        )
        val rawTailStartIndex = 1

        val widened = ContextCompactionPlanner.automaticTailStartIndex(
            messages = messages,
            rawTailStartIndex = rawTailStartIndex,
            keepRecentToolCalls = 2,
            maxTailTokens = 1,
        )

        assertTrue(widened > rawTailStartIndex)
    }

    @Test
    fun `maxTailTokens targets 50 percent by default and 35 percent when precision compaction is enabled`() {
        val trigger = 10_000
        val target = 1_000

        // Default: 10_000 * 50% - 1_000 = 4_000 tokens for tail
        val defaultBudget = ContextCompactionPlanner.maxTailTokens(
            triggerTokens = trigger,
            targetTokens = target,
            enablePrecisionCompaction = false,
        )
        assertEquals(4_000, defaultBudget)

        // S2 precision hysteresis: 10_000 * 35% - 1_000 = 2_500 tokens for tail
        // (total request target lands at 35% of trigger, leaving 65% buffer before next compaction)
        val precisionBudget = ContextCompactionPlanner.maxTailTokens(
            triggerTokens = trigger,
            targetTokens = target,
            enablePrecisionCompaction = true,
        )
        assertEquals(2_500, precisionBudget)
    }

    @Test
    fun `maxTailTokens clamps to minimum automatic tail budget tokens`() {
        val trigger = 1_000
        val target = 800

        // 1_000 * 35% - 800 = -450 -> clamped to 512
        val clamped = ContextCompactionPlanner.maxTailTokens(
            triggerTokens = trigger,
            targetTokens = target,
            enablePrecisionCompaction = true,
        )
        assertEquals(ContextCompactionPlanner.MIN_AUTOMATIC_TAIL_BUDGET_TOKENS, clamped)

        // trigger == null -> null
        val nullResult = ContextCompactionPlanner.maxTailTokens(
            triggerTokens = null,
            targetTokens = target,
            enablePrecisionCompaction = true,
        )
        assertEquals(null, nullResult)
    }

    @Test
    fun `deduplicateToolRecords folds repeated identical reads and keeps latest with count`() {
        val records = listOf(
            buildString {
                appendLine("- Call ID: call-1")
                appendLine("- Tool: workspace_read_file")
                appendLine("  Result: content-v1")
                appendLine("  Input: {\"path\": \"/workspace/file.txt\"}")
            }.trim(),
            buildString {
                appendLine("- Call ID: call-2")
                appendLine("- Tool: workspace_read_file")
                appendLine("  Result: content-v2")
                appendLine("  Input: {\"path\": \"/workspace/file.txt\"}")
            }.trim(),
            buildString {
                appendLine("- Call ID: call-3")
                appendLine("- Tool: workspace_read_file")
                appendLine("  Result: content-v3")
                appendLine("  Input: {\"path\": \"/workspace/file.txt\"}")
            }.trim(),
        )

        val deduplicated = ContextCompactionPlanner.deduplicateToolRecords(records)
        assertEquals(1, deduplicated.size)
        assertTrue(deduplicated.single().contains("- Tool: workspace_read_file (repeated 3 times)"))
        assertTrue(deduplicated.single().contains("Result: content-v3"))
        assertTrue(deduplicated.single().contains("Call ID: call-3"))
    }

    @Test
    fun `deduplicateToolRecords write creates fence for targeted read group`() {
        val records = listOf(
            buildString {
                appendLine("- Call ID: call-1")
                appendLine("- Tool: workspace_read_file")
                appendLine("  Result: content-v1")
                appendLine("  Input: {\"path\": \"/workspace/file.txt\"}")
            }.trim(),
            buildString {
                appendLine("- Call ID: call-2")
                appendLine("- Tool: workspace_edit_file")
                appendLine("  Result: {\"ok\": true}")
                appendLine("  Input: {\"path\": \"/workspace/file.txt\", \"new_text\": \"v2\"}")
            }.trim(),
            buildString {
                appendLine("- Call ID: call-3")
                appendLine("- Tool: workspace_read_file")
                appendLine("  Result: content-v2")
                appendLine("  Input: {\"path\": \"/workspace/file.txt\"}")
            }.trim(),
        )

        val deduplicated = ContextCompactionPlanner.deduplicateToolRecords(records)
        assertEquals(3, deduplicated.size)
        assertEquals("call-1", Regex("""Call ID:\s*([^\n]+)""").find(deduplicated[0])?.groupValues?.get(1)?.trim())
        assertEquals("call-2", Regex("""Call ID:\s*([^\n]+)""").find(deduplicated[1])?.groupValues?.get(1)?.trim())
        assertEquals("call-3", Regex("""Call ID:\s*([^\n]+)""").find(deduplicated[2])?.groupValues?.get(1)?.trim())
    }

    @Test
    fun `deduplicateToolRecords shell flushes all active reads and errors are preserved`() {
        val records = listOf(
            buildString {
                appendLine("- Call ID: call-1")
                appendLine("- Tool: workspace_read_file")
                appendLine("  Result: {\"error\": \"file not found\"}")
                appendLine("  Input: {\"path\": \"/workspace/a.txt\"}")
            }.trim(),
            buildString {
                appendLine("- Call ID: call-2")
                appendLine("- Tool: workspace_read_file")
                appendLine("  Result: v1")
                appendLine("  Input: {\"path\": \"/workspace/a.txt\"}")
            }.trim(),
            buildString {
                appendLine("- Call ID: call-3")
                appendLine("- Tool: workspace_shell")
                appendLine("  Result: {\"exitCode\": 0, \"stdout\": \"done\"}")
                appendLine("  Input: {\"command\": \"git status\"}")
            }.trim(),
            buildString {
                appendLine("- Call ID: call-4")
                appendLine("- Tool: workspace_read_file")
                appendLine("  Result: v2")
                appendLine("  Input: {\"path\": \"/workspace/a.txt\"}")
            }.trim(),
        )

        val deduplicated = ContextCompactionPlanner.deduplicateToolRecords(records)
        assertEquals(4, deduplicated.size)
        assertTrue(deduplicated[0].contains("error"))
        assertTrue(deduplicated[2].contains("workspace_shell"))
    }

    @Test
    fun `mandatoryToolExecutionDigest with enablePrecisionCompaction applies deduplication`() {
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    executedTool("workspace_read_file", "{\"path\": \"/a.txt\"}", "res1"),
                    executedTool("workspace_read_file", "{\"path\": \"/a.txt\"}", "res2"),
                ),
            ),
        )

        val legacy = ContextCompactionPlanner.mandatoryToolExecutionDigest(
            messages = messages,
            maxTokens = 2000,
            enablePrecisionCompaction = false,
        )
        val deduplicated = ContextCompactionPlanner.mandatoryToolExecutionDigest(
            messages = messages,
            maxTokens = 2000,
            enablePrecisionCompaction = true,
        )

        assertEquals(2, Regex(Regex.escape(ContextCompactionPlanner.TOOL_RECORD_HEADER)).findAll(legacy).count())
        assertEquals(1, Regex(Regex.escape(ContextCompactionPlanner.TOOL_RECORD_HEADER)).findAll(deduplicated).count())
        assertTrue(deduplicated.contains("repeated 2 times"))
    }

    @Test
    fun `pre-dry mapSourceText bounds huge reasoning and text only when precision compaction is enabled`() {
        val hugeReasoning = "REASON-HEAD-" + "r".repeat(5_000) + "-REASON-TAIL"
        val hugeText = "TEXT-HEAD-" + "t".repeat(12_000) + "-TEXT-TAIL"
        val hugeToolOutput = "TOOL-HEAD-" + "x".repeat(100_000) + "-TOOL-TAIL"
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = hugeReasoning),
                UIMessagePart.Text(text = hugeText),
                executedTool("workspace_read_file", "{\"path\":\"/workspace/a.kt\"}", hugeToolOutput),
            ),
        )

        val defaultMap = ContextCompactionPlanner.mapSourceText(
            message = message,
            enablePrecisionCompaction = false,
        )
        // Flag off: tool output is previewed, but text and reasoning stay verbatim.
        assertTrue(defaultMap.contains(hugeReasoning))
        assertTrue(defaultMap.contains(hugeText))
        assertTrue(defaultMap.contains("preview truncated for map request"))
        assertFalse(defaultMap.contains("chars omitted"))

        val preDryMap = ContextCompactionPlanner.mapSourceText(
            message = message,
            enablePrecisionCompaction = true,
        )
        // Flag on: 100k tool result, 5k reasoning, and 12k text are all bounded with head + tail.
        assertTrue(preDryMap.contains("REASON-HEAD-"))
        assertTrue(preDryMap.contains("-REASON-TAIL"))
        assertTrue(preDryMap.contains("TEXT-HEAD-"))
        assertTrue(preDryMap.contains("-TEXT-TAIL"))
        assertTrue(preDryMap.contains("TOOL-HEAD-"))
        assertTrue(preDryMap.contains("-TOOL-TAIL"))
        assertTrue(preDryMap.contains("chars omitted"))
        assertFalse(preDryMap.contains(hugeReasoning))
        assertFalse(preDryMap.contains(hugeText))
        assertFalse(preDryMap.contains(hugeToolOutput))
        assertTrue(preDryMap.length < 8_000)

        // Deterministic tool digest still sees original stored message parts.
        val digest = ContextCompactionPlanner.mandatoryToolExecutionDigest(
            messages = listOf(message),
            maxTokens = 4_000,
        )
        assertTrue(digest.contains("workspace_read_file"))
        assertTrue(digest.contains("TOOL-HEAD-"))
    }

    @Test
    fun `pre-dry truncation preserves surrogate pairs at split boundaries`() {
        // Construct text where the 3/4 head boundary and 1/4 tail boundary land on a surrogate pair (😀 = \uD83D\uDE00)
        val headChars = ContextCompactionPlanner.PRE_DRY_REASONING_PREVIEW_CHARS * 3 / 4
        val tailChars = ContextCompactionPlanner.PRE_DRY_REASONING_PREVIEW_CHARS - headChars
        val prefix = "a".repeat(headChars - 1)
        val middle = "m".repeat(500)
        val suffix = "z".repeat(tailChars - 1)
        val reasoningWithEmoji = prefix + "😀" + middle + "😀" + suffix

        val mapText = ContextCompactionPlanner.mapSourceText(
            message = UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Reasoning(reasoning = reasoningWithEmoji)),
            ),
            enablePrecisionCompaction = true,
        )

        assertTrue(mapText.contains("chars omitted"))
        for (i in mapText.indices) {
            val c = mapText[i]
            if (Character.isHighSurrogate(c)) {
                assertTrue("Unpaired high surrogate at $i", i + 1 < mapText.length && Character.isLowSurrogate(mapText[i + 1]))
            } else if (Character.isLowSurrogate(c)) {
                assertTrue("Unpaired low surrogate at $i", i > 0 && Character.isHighSurrogate(mapText[i - 1]))
            }
        }
    }

    @Test
    fun `precisionCompactionSlotContractInstructions contains all five fixed slot headings and anti-fabrication rule`() {
        val contract = ContextCompactionPlanner.precisionCompactionSlotContractInstructions()

        assertTrue(contract.contains("目标与约束"))
        assertTrue(contract.contains("已改文件与 SHA"))
        assertTrue(contract.contains("已验证结论与证据路径"))
        assertTrue(contract.contains("失败与回滚"))
        assertTrue(contract.contains("未完成阻塞"))
        assertTrue(contract.contains("Do not invent facts"))
    }

    private fun toolMessage(name: String) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(
            executedTool(name, "{}", "result"),
        ),
    )

    private fun executedTool(name: String, input: String, output: String) = UIMessagePart.Tool(
        toolCallId = "call-$name",
        toolName = name,
        input = input,
        output = listOf(UIMessagePart.Text(output)),
    )
}
