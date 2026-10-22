package me.rerere.rikkahub.subagent

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-04 / (4) - the materialisation of `context_refs` into sub-agent task text.
 *
 * These assertions are the contract the engine relies on. In particular the "nothing to
 * carry" cases must return null / the untouched task, because that is what makes the
 * feature byte-for-byte inert when the flag is off or the caller has no history.
 */
class SubAgentContextDigestTest {

    private fun msg(role: MessageRole, text: String) =
        UIMessage(role = role, parts = listOf(UIMessagePart.Text(text)))

    private fun user(text: String) = msg(MessageRole.USER, text)
    private fun assistant(text: String) = msg(MessageRole.ASSISTANT, text)

    // ---- turnsFrom ------------------------------------------------------------------

    @Test
    fun `no messages produce no context`() {
        assertTrue(SubAgentContextDigest.turnsFrom(emptyList()).isEmpty())
        assertNull(SubAgentContextDigest.render(emptyList()))
    }

    @Test
    fun `messages that carry no text produce no context`() {
        val toolOnly = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())
        val blank = user("   \n  ")
        assertTrue(SubAgentContextDigest.turnsFrom(listOf(toolOnly, blank)).isEmpty())
        assertNull(SubAgentContextDigest.render(SubAgentContextDigest.turnsFrom(listOf(toolOnly, blank))))
    }

    @Test
    fun `turns keep conversation order and lowercased role names`() {
        val turns = SubAgentContextDigest.turnsFrom(
            listOf(user("first"), assistant("second"), user("third"))
        )
        assertEquals(listOf("user", "assistant", "user"), turns.map { it.role })
        assertEquals(listOf("first", "second", "third"), turns.map { it.text })
    }

    @Test
    fun `the in-flight trailing assistant message is not carried`() {
        // At dispatch time the newest assistant message is the one holding the dispatch
        // call itself. Feeding it back would show the sub-agent a half-finished turn.
        val turns = SubAgentContextDigest.turnsFrom(
            listOf(user("what is the plan"), assistant("here is the dispatch tool call"))
        )
        assertEquals(listOf("user"), turns.map { it.role })
        assertEquals(listOf("what is the plan"), turns.map { it.text })
    }

    @Test
    fun `only the newest N turns are carried`() {
        val messages = (1..8).flatMap { listOf(user("u$it"), assistant("a$it")) }
        val turns = SubAgentContextDigest.turnsFrom(messages, maxTurns = 3)
        assertEquals(3, turns.size)
        // a8 is the in-flight turn and is dropped first, so the window is u7/a7/u8.
        assertEquals(listOf("u7", "a7", "u8"), turns.map { it.text })
    }

    @Test
    fun `maxTurns zero and negative mean no context`() {
        val messages = listOf(user("hello"), user("world"))
        assertTrue(SubAgentContextDigest.turnsFrom(messages, maxTurns = 0).isEmpty())
        assertTrue(SubAgentContextDigest.turnsFrom(messages, maxTurns = -5).isEmpty())
    }

    @Test
    fun `maxTurns above the hard cap is clamped, not honoured`() {
        val messages = (1..25).map { user("t$it") }
        assertEquals(
            SubAgentContextDigest.MAX_TURNS,
            SubAgentContextDigest.turnsFrom(messages, maxTurns = 999).size,
        )
    }

    @Test
    fun `a turn with mixed parts keeps only its text`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("visible"),
                UIMessagePart.Text("also visible"),
            ),
        )
        // `message` is not last on purpose: a TRAILING assistant message is the in-flight
        // turn and is dropped (see the dedicated test above).
        val turns = SubAgentContextDigest.turnsFrom(listOf(message, user("q")))
        assertEquals(2, turns.size)
        assertEquals("visible\nalso visible", turns[0].text)
        assertEquals("q", turns[1].text)
    }

    // ---- mediaPartsFrom (D3) --------------------------------------------------------

    private fun userWithMedia(vararg parts: UIMessagePart) =
        UIMessage(role = MessageRole.USER, parts = parts.toList())

    @Test
    fun `media comes from the newest user message only`() {
        val older = userWithMedia(UIMessagePart.Audio("file:///old.mp3"))
        val newest = userWithMedia(
            UIMessagePart.Text("look at this"),
            UIMessagePart.Video("file:///new.mp4"),
            UIMessagePart.Audio("file:///new.mp3"),
        )
        val media = SubAgentContextDigest.mediaPartsFrom(listOf(older, assistant("ok"), newest))
        assertEquals(2, media.size)
        assertEquals(
            listOf("file:///new.mp4", "file:///new.mp3"),
            media.map {
                when (it) {
                    is UIMessagePart.Video -> it.url
                    is UIMessagePart.Audio -> it.url
                    else -> error("unexpected media part: $it")
                }
            },
        )
    }

    @Test
    fun `no user message carries no media`() {
        assertTrue(SubAgentContextDigest.mediaPartsFrom(emptyList()).isEmpty())
        assertTrue(SubAgentContextDigest.mediaPartsFrom(listOf(assistant("hi"))).isEmpty())
    }

    @Test
    fun `a text-only user message carries no media`() {
        assertTrue(SubAgentContextDigest.mediaPartsFrom(listOf(user("plain text"))).isEmpty())
    }

    @Test
    fun `images are still not carried, only audio and video`() {
        val message = userWithMedia(
            UIMessagePart.Image("file:///pic.png"),
            UIMessagePart.Audio("file:///note.mp3"),
        )
        val media = SubAgentContextDigest.mediaPartsFrom(listOf(message))
        assertEquals(1, media.size)
        assertTrue(media.single() is UIMessagePart.Audio)
    }

    @Test
    fun `a request defaults to carrying no parent media`() {
        assertFalse(SubAgentRequest(task = "t").attachParentMedia)
        assertTrue(SubAgentRequest(task = "t", attachParentMedia = true).attachParentMedia)
    }

    // ---- render ---------------------------------------------------------------------

    @Test
    fun `render is fenced and names the task as the instruction`() {
        val out = SubAgentContextDigest.render(listOf(SubAgentContextTurn("user", "hello")))!!
        assertTrue(out.startsWith(SubAgentContextDigest.HEADER))
        assertTrue(out.endsWith(SubAgentContextDigest.FOOTER))
        assertTrue(out.contains("the TASK below is what you must do"))
        assertTrue(out.contains("[user] hello"))
    }

    @Test
    fun `an over-long turn keeps its head and its tail`() {
        val head = "H".repeat(5000)
        val tail = "T".repeat(5000)
        val out = SubAgentContextDigest.render(listOf(SubAgentContextTurn("user", head + tail)))!!
        assertTrue(out.contains(SubAgentContextDigest.TRUNCATION_MARKER))
        assertTrue(out.contains("HHHH"))
        assertTrue(out.contains("TTTT"))
    }

    @Test
    fun `a clamped turn never exceeds the per-turn cap`() {
        val monster = "x".repeat(50_000)
        val out = SubAgentContextDigest.render(listOf(SubAgentContextTurn("user", monster)))!!
        // turnsFrom deliberately does NOT clamp - it reports the conversation as it is, and
        // render owns the budget. What must hold is that the rendered block stays small.
        val fixedOverhead = SubAgentContextDigest.HEADER.length + SubAgentContextDigest.FOOTER.length
        assertTrue(out.length <= SubAgentContextDigest.MAX_CHARS_PER_TURN + fixedOverhead + 32)
    }

    @Test
    fun `a short turn is never truncated`() {
        val text = "y".repeat(SubAgentContextDigest.MAX_CHARS_PER_TURN)
        val out = SubAgentContextDigest.render(listOf(SubAgentContextTurn("user", text)))!!
        assertFalse(out.contains(SubAgentContextDigest.TRUNCATION_MARKER))
    }

    @Test
    fun `the total budget drops the oldest turns first`() {
        val big = "z".repeat(1500)
        val turns = (1..6).map { SubAgentContextTurn("user", "$big-$it") }
        val out = SubAgentContextDigest.render(turns)!!
        // The newest turn is the one the task refers to; it must survive.
        assertTrue(out.contains("-6"))
        assertFalse(out.contains("-1"))
        assertTrue(out.contains("omitted for length"))
        assertTrue(out.length < SubAgentContextDigest.MAX_TOTAL_CHARS + 500)
    }

    @Test
    fun `everything fits when the caller stays inside the budgets`() {
        val turns = (1..10).map { SubAgentContextTurn("user", "short $it") }
        val out = SubAgentContextDigest.render(turns)!!
        assertFalse(out.contains("omitted for length"))
        (1..10).forEach { assertTrue(out.contains("short $it")) }
    }

    @Test
    fun `render re-normalises roles and drops blank turns`() {
        val out = SubAgentContextDigest.render(
            listOf(
                SubAgentContextTurn("  USER  ", "  spaced  "),
                SubAgentContextTurn("assistant", "   "),
            )
        )!!
        assertTrue(out.contains("[user] spaced"))
        assertFalse(out.contains("[assistant]"))
    }

    @Test
    fun `render takes only the newest MAX_TURNS turns`() {
        val turns = (1..(SubAgentContextDigest.MAX_TURNS + 5)).map { SubAgentContextTurn("user", "t$it") }
        val out = SubAgentContextDigest.render(turns)!!
        assertFalse(out.contains("[user] t1\n"))
        assertTrue(out.contains("[user] t${SubAgentContextDigest.MAX_TURNS + 5}"))
    }

    // ---- prependToTask --------------------------------------------------------------

    @Test
    fun `prependToTask leaves the task untouched when there is nothing to carry`() {
        assertEquals("do the thing", SubAgentContextDigest.prependToTask(emptyList(), "do the thing"))
        assertEquals(
            "do the thing",
            SubAgentContextDigest.prependToTask(listOf(SubAgentContextTurn("user", "  ")), "do the thing"),
        )
    }

    @Test
    fun `prependToTask puts the context before the task`() {
        val out = SubAgentContextDigest.prependToTask(
            listOf(SubAgentContextTurn("user", "earlier")),
            "do the thing",
        )
        assertTrue(out.endsWith("do the thing"))
        assertTrue(out.indexOf("earlier") < out.indexOf("do the thing"))
        assertTrue(out.contains(SubAgentContextDigest.FOOTER))
    }

    // ---- request plumbing -----------------------------------------------------------

    @Test
    fun `a request defaults to carrying no context`() {
        val request = SubAgentRequest(task = "t")
        assertNull(request.contextRefs)
        assertEquals(
            SubAgentRequestValidator.Result.Ok(request),
            SubAgentRequestValidator.validate(request),
        )
    }

    @Test
    fun `a request may not ask for more turns than the cap`() {
        val tooMany = SubAgentRequest(
            task = "t",
            contextRefs = SubAgentContextRefs(recentTurns = SubAgentContextDigest.MAX_TURNS + 1),
        )
        val result = SubAgentRequestValidator.validate(tooMany)
        assertTrue(result is SubAgentRequestValidator.Result.Reject)
        assertEquals("invalid_context_refs", (result as SubAgentRequestValidator.Result.Reject).error)
    }

    @Test
    fun `a negative turn count is rejected too`() {
        val negative = SubAgentRequest(task = "t", contextRefs = SubAgentContextRefs(recentTurns = -1))
        val result = SubAgentRequestValidator.validate(negative)
        assertTrue(result is SubAgentRequestValidator.Result.Reject)
        assertEquals("invalid_context_refs", (result as SubAgentRequestValidator.Result.Reject).error)
    }

    @Test
    fun `the boundary turn counts validate`() {
        listOf(0, 1, SubAgentContextDigest.MAX_TURNS).forEach { n ->
            val request = SubAgentRequest(task = "t", contextRefs = SubAgentContextRefs(recentTurns = n))
            assertTrue(
                "recentTurns=$n should validate",
                SubAgentRequestValidator.validate(request) is SubAgentRequestValidator.Result.Ok,
            )
        }
    }
}
