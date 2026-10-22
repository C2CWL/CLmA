package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-06 / (7) — regression tests for the cold-memory tool surface and the file-name rules behind
 * it.
 *
 * Two layers are pinned down here:
 *  - [ColdMemoryRules] is pure logic (directory normalisation, write-name validation, reference
 *    resolution, read windowing) and is tested directly;
 *  - the three tools are tested against an in-memory fake directory, so the JSON envelopes and
 *    the approval flags are part of the contract rather than an implementation detail.
 */
class ColdMemoryToolsTest {

    private class FakeDirectory(initial: Map<String, String> = emptyMap()) {
        val store: MutableMap<String, String> = initial.toMutableMap()
        var lastAppend: Boolean? = null
        var writeCount: Int = 0

        fun tools(): List<Tool> = buildColdMemoryTools(
            dirLabel = "/workspace/memory",
            listDocs = {
                store.entries.map { ColdMemoryDoc(it.key, it.value.length.toLong()) }
            },
            readDoc = { name -> store[name] },
            writeDoc = { name, content, append ->
                lastAppend = append
                writeCount += 1
                val next = if (append) store[name].orEmpty() + content else content
                store[name] = next
                ColdMemoryWriteResult(
                    fileName = name,
                    mode = if (append) "append" else "overwrite",
                    totalChars = next.length,
                )
            },
        )
    }

    private fun toolNamed(tools: List<Tool>, name: String): Tool = tools.first { it.name == name }

    private fun executeJson(tool: Tool, args: String): String = runBlocking {
        val parts = tool.execute(Json.parseToJsonElement(args))
        (parts.single() as UIMessagePart.Text).text
    }

    // ---- ColdMemoryRules.normalizeDir ------------------------------------------------------

    @Test
    fun `normalizeDir strips the workspace prefix`() {
        assertEquals("notes/memory", ColdMemoryRules.normalizeDir("/workspace/notes/memory"))
    }

    @Test
    fun `normalizeDir accepts an already relative path`() {
        assertEquals("notes/memory", ColdMemoryRules.normalizeDir("notes/memory"))
    }

    @Test
    fun `normalizeDir treats the workspace root as the empty path`() {
        assertEquals("", ColdMemoryRules.normalizeDir("/workspace"))
        assertEquals("", ColdMemoryRules.normalizeDir("/workspace/"))
    }

    @Test
    fun `normalizeDir rejects blank input`() {
        assertNull(ColdMemoryRules.normalizeDir(null))
        assertNull(ColdMemoryRules.normalizeDir(""))
        assertNull(ColdMemoryRules.normalizeDir("   "))
    }

    @Test
    fun `normalizeDir rejects any attempt to escape the workspace`() {
        assertNull(ColdMemoryRules.normalizeDir("/workspace/../etc"))
        assertNull(ColdMemoryRules.normalizeDir("notes/../../etc"))
        assertNull(ColdMemoryRules.normalizeDir(".."))
    }

    @Test
    fun `normalizeDir trims separators and leading dot segments`() {
        assertEquals("notes", ColdMemoryRules.normalizeDir("/workspace/notes/"))
        assertEquals("notes", ColdMemoryRules.normalizeDir("./notes"))
    }

    // ---- ColdMemoryRules.isValidWriteName --------------------------------------------------

    @Test
    fun `write names must be a single markdown segment`() {
        assertTrue(ColdMemoryRules.isValidWriteName("M13-new-topic.md"))
        assertTrue(ColdMemoryRules.isValidWriteName("NOTES.MD"))
        assertFalse(ColdMemoryRules.isValidWriteName("notes"))
        assertFalse(ColdMemoryRules.isValidWriteName("notes.txt"))
        assertFalse(ColdMemoryRules.isValidWriteName("dir/notes.md"))
        assertFalse(ColdMemoryRules.isValidWriteName("dir\\notes.md"))
        assertFalse(ColdMemoryRules.isValidWriteName("../notes.md"))
        assertFalse(ColdMemoryRules.isValidWriteName(".."))
        assertFalse(ColdMemoryRules.isValidWriteName(""))
    }

    // ---- ColdMemoryRules.lookup ------------------------------------------------------------

    private val names = listOf(
        "INDEX.md",
        "M01-device-shell.md",
        "M02-device-performance.md",
        "M03-google-framework.md",
    )

    @Test
    fun `lookup prefers an exact file name`() {
        val found = ColdMemoryRules.lookup("M01-device-shell.md", names)
        assertEquals(ColdMemoryLookup.Found("M01-device-shell.md"), found)
    }

    @Test
    fun `lookup accepts a name without the markdown suffix`() {
        val found = ColdMemoryRules.lookup("M02-device-performance", names)
        assertEquals(ColdMemoryLookup.Found("M02-device-performance.md"), found)
    }

    @Test
    fun `lookup resolves a unique numeric prefix`() {
        val found = ColdMemoryRules.lookup("M03", names)
        assertEquals(ColdMemoryLookup.Found("M03-google-framework.md"), found)
    }

    @Test
    fun `lookup reports ambiguity instead of guessing`() {
        val found = ColdMemoryRules.lookup("M0", names)
        assertTrue(found is ColdMemoryLookup.Ambiguous)
        assertEquals(3, (found as ColdMemoryLookup.Ambiguous).candidates.size)
    }

    @Test
    fun `lookup reports not found for an unknown reference`() {
        assertEquals(ColdMemoryLookup.NotFound, ColdMemoryRules.lookup("zzz", names))
        assertEquals(ColdMemoryLookup.NotFound, ColdMemoryRules.lookup("", names))
    }

    // ---- ColdMemoryRules.window ------------------------------------------------------------

    @Test
    fun `window returns the whole short document`() {
        val window = ColdMemoryRules.window("hello", 0)
        assertEquals("hello", window.content)
        assertEquals(5, window.totalChars)
        assertFalse(window.truncated)
    }

    @Test
    fun `window truncates a long document and offers a continuation point`() {
        val text = "a".repeat(ColdMemoryRules.READ_WINDOW_CHARS + 100)
        val window = ColdMemoryRules.window(text, 0)
        assertEquals(ColdMemoryRules.READ_WINDOW_CHARS, window.content.length)
        assertEquals(ColdMemoryRules.READ_WINDOW_CHARS, window.endExclusive)
        assertTrue(window.truncated)
    }

    @Test
    fun `window continues from an explicit offset`() {
        val text = "a".repeat(ColdMemoryRules.READ_WINDOW_CHARS + 100)
        val window = ColdMemoryRules.window(text, ColdMemoryRules.READ_WINDOW_CHARS)
        assertEquals(100, window.content.length)
        assertFalse(window.truncated)
    }

    @Test
    fun `window clamps a negative or oversized offset`() {
        assertEquals(0, ColdMemoryRules.window("abc", -5).start)
        assertEquals(3, ColdMemoryRules.window("abc", 99).start)
        assertEquals("", ColdMemoryRules.window("abc", 99).content)
    }

    // ---- memory_index ----------------------------------------------------------------------

    @Test
    fun `the tool set is memory_index memory_read and memory_write`() {
        val tools = FakeDirectory().tools()
        assertEquals(listOf("memory_index", "memory_read", "memory_write"), tools.map { it.name })
    }

    @Test
    fun `memory_index lists markdown files and echoes the table of contents`() {
        val dir = FakeDirectory(
            mapOf(
                "INDEX.md" to "M01 - shell\nM02 - performance",
                "M01-device-shell.md" to "shell body",
                "notes.txt" to "not markdown",
            )
        )
        val json = Json.parseToJsonElement(
            executeJson(toolNamed(dir.tools(), "memory_index"), "{}")
        ).jsonObject

        assertEquals("/workspace/memory", json["dir"]!!.jsonPrimitive.content)
        assertEquals(2, json["fileCount"]!!.jsonPrimitive.int)
        assertEquals("M01 - shell\nM02 - performance", json["index"]!!.jsonPrimitive.content)

        val listed = json["files"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(listOf("INDEX.md", "M01-device-shell.md"), listed)
    }

    @Test
    fun `memory_index says so when there is no table of contents`() {
        val dir = FakeDirectory(mapOf("M01-a.md" to "body"))
        val json = Json.parseToJsonElement(
            executeJson(toolNamed(dir.tools(), "memory_index"), "{}")
        ).jsonObject

        assertEquals("", json["index"]!!.jsonPrimitive.content)
        assertTrue(json["note"]!!.jsonPrimitive.content.contains("INDEX.md"))
    }

    // ---- memory_read -----------------------------------------------------------------------

    @Test
    fun `memory_read accepts a unique fragment and returns the body`() {
        val dir = FakeDirectory(mapOf("M01-device-shell.md" to "shell body"))
        val json = Json.parseToJsonElement(
            executeJson(toolNamed(dir.tools(), "memory_read"), """{"file":"M01"}""")
        ).jsonObject

        assertEquals("M01-device-shell.md", json["file"]!!.jsonPrimitive.content)
        assertEquals("shell body", json["content"]!!.jsonPrimitive.content)
        assertEquals(10, json["totalChars"]!!.jsonPrimitive.int)
        assertFalse(json["truncated"]!!.jsonPrimitive.boolean)
        assertNull(json["nextStart"])
    }

    @Test
    fun `memory_read flags a truncated window with a continuation offset`() {
        val body = "x".repeat(ColdMemoryRules.READ_WINDOW_CHARS + 10)
        val dir = FakeDirectory(mapOf("M01-long.md" to body))
        val json = Json.parseToJsonElement(
            executeJson(toolNamed(dir.tools(), "memory_read"), """{"file":"M01"}""")
        ).jsonObject

        assertTrue(json["truncated"]!!.jsonPrimitive.boolean)
        assertEquals(ColdMemoryRules.READ_WINDOW_CHARS, json["nextStart"]!!.jsonPrimitive.int)
    }

    @Test
    fun `memory_read resumes from start`() {
        val body = "x".repeat(ColdMemoryRules.READ_WINDOW_CHARS) + "TAIL"
        val dir = FakeDirectory(mapOf("M01-long.md" to body))
        val json = Json.parseToJsonElement(
            executeJson(
                toolNamed(dir.tools(), "memory_read"),
                """{"file":"M01","start":${ColdMemoryRules.READ_WINDOW_CHARS}}""",
            )
        ).jsonObject

        assertEquals("TAIL", json["content"]!!.jsonPrimitive.content)
        assertFalse(json["truncated"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `memory_read lists candidates when the reference is ambiguous`() {
        val dir = FakeDirectory(mapOf("M01-a.md" to "a", "M02-b.md" to "b"))
        val json = Json.parseToJsonElement(
            executeJson(toolNamed(dir.tools(), "memory_read"), """{"file":"M0"}""")
        ).jsonObject

        assertEquals("ambiguous", json["error"]!!.jsonPrimitive.content)
        assertEquals(2, json["candidates"]!!.jsonArray.size)
    }

    @Test
    fun `memory_read lists what exists when nothing matches`() {
        val dir = FakeDirectory(mapOf("M01-a.md" to "a"))
        val json = Json.parseToJsonElement(
            executeJson(toolNamed(dir.tools(), "memory_read"), """{"file":"nope"}""")
        ).jsonObject

        assertEquals("not_found", json["error"]!!.jsonPrimitive.content)
        assertEquals(1, json["available"]!!.jsonArray.size)
    }

    @Test
    fun `memory_read requires the file argument`() {
        val json = Json.parseToJsonElement(
            executeJson(toolNamed(FakeDirectory().tools(), "memory_read"), "{}")
        ).jsonObject

        assertEquals("missing_argument", json["error"]!!.jsonPrimitive.content)
    }

    // ---- memory_write ----------------------------------------------------------------------

    @Test
    fun `memory_write appends by default`() {
        val dir = FakeDirectory(mapOf("M01-a.md" to "first\n"))
        val json = Json.parseToJsonElement(
            executeJson(
                toolNamed(dir.tools(), "memory_write"),
                """{"file":"M01-a.md","content":"second\n"}""",
            )
        ).jsonObject

        assertEquals("append", json["mode"]!!.jsonPrimitive.content)
        assertEquals("first\nsecond\n", dir.store["M01-a.md"])
        assertTrue(dir.lastAppend == true)
        assertEquals(13, json["totalChars"]!!.jsonPrimitive.int)
    }

    @Test
    fun `memory_write overwrite replaces the file`() {
        val dir = FakeDirectory(mapOf("M01-a.md" to "old"))
        executeJson(
            toolNamed(dir.tools(), "memory_write"),
            """{"file":"M01-a.md","content":"new","mode":"overwrite"}""",
        )

        assertEquals("new", dir.store["M01-a.md"])
        assertFalse(dir.lastAppend == true)
    }

    @Test
    fun `memory_write creates a file that does not exist yet`() {
        val dir = FakeDirectory()
        executeJson(
            toolNamed(dir.tools(), "memory_write"),
            """{"file":"M13-new.md","content":"# new"}""",
        )

        assertEquals("# new", dir.store["M13-new.md"])
    }

    @Test
    fun `memory_write refuses a name that could leave the directory`() {
        val dir = FakeDirectory()
        val json = Json.parseToJsonElement(
            executeJson(
                toolNamed(dir.tools(), "memory_write"),
                """{"file":"../escape.md","content":"x"}""",
            )
        ).jsonObject

        assertEquals("invalid_name", json["error"]!!.jsonPrimitive.content)
        assertEquals(0, dir.writeCount)
    }

    @Test
    fun `memory_write refuses a non markdown target`() {
        val dir = FakeDirectory()
        val json = Json.parseToJsonElement(
            executeJson(
                toolNamed(dir.tools(), "memory_write"),
                """{"file":"notes.txt","content":"x"}""",
            )
        ).jsonObject

        assertEquals("invalid_name", json["error"]!!.jsonPrimitive.content)
        assertEquals(0, dir.writeCount)
    }

    @Test
    fun `memory_write requires content`() {
        val dir = FakeDirectory()
        val json = Json.parseToJsonElement(
            executeJson(toolNamed(dir.tools(), "memory_write"), """{"file":"M01-a.md"}""")
        ).jsonObject

        assertEquals("missing_argument", json["error"]!!.jsonPrimitive.content)
        assertEquals(0, dir.writeCount)
    }

    // ---- approval + schema contract --------------------------------------------------------

    @Test
    fun `only memory_write needs approval`() {
        val tools = FakeDirectory().tools()
        val args = Json.parseToJsonElement("{}")
        assertFalse(toolNamed(tools, "memory_index").needsApproval(args))
        assertFalse(toolNamed(tools, "memory_read").needsApproval(args))
        assertTrue(toolNamed(tools, "memory_write").needsApproval(args))
    }

    @Test
    fun `memory_index takes no arguments`() {
        val schema = toolNamed(FakeDirectory().tools(), "memory_index").parameters() as InputSchema.Obj
        assertTrue(schema.properties.isEmpty())
        assertTrue(schema.required.isNullOrEmpty())
    }

    @Test
    fun `memory_read requires file and documents start`() {
        val schema = toolNamed(FakeDirectory().tools(), "memory_read").parameters() as InputSchema.Obj
        assertEquals(listOf("file"), schema.required)
        assertTrue(schema.properties.containsKey("start"))
        assertEquals("integer", schema.properties["start"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `memory_write requires file and content and offers the two modes`() {
        val schema = toolNamed(FakeDirectory().tools(), "memory_write").parameters() as InputSchema.Obj
        assertEquals(listOf("file", "content"), schema.required)

        val modes = schema.properties["mode"]!!.jsonObject["enum"]!!.jsonArray
            .map { it.jsonPrimitive.content }
        assertEquals(listOf("append", "overwrite"), modes)
    }
}
