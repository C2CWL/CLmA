package com.clma.app.data.ai.transformers

import com.clma.ai.core.MessageRole
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import com.clma.app.data.ai.DocumentTokenEstimates
import com.clma.app.data.ai.document.DocumentInlineNotices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Coverage for [DocumentAsPromptTransformer.resolveWorkspacePathForFile] — the pure,
 * android.net.Uri-free part of the path-advertising gate shared by Document and Image parts
 * (issue #37, second bullet: generated images should be advertised the same way uploaded
 * documents are, but only for files that really live under filesDir/upload).
 *
 * Note: the android.net.Uri parsing step (`String.toUri()`) that sits in front of this
 * function is NOT covered here. The :app module's JVM unit tests run against the unmocked
 * Android stub jar (no Robolectric, no `unitTests.isReturnDefaultValues`), so
 * `android.net.Uri.parse` throws and is swallowed by the surrounding `runCatching`, making
 * `data:`/`http(s)` vs. `file:` URLs indistinguishable in this test environment (see the same
 * caveat in DownloadToolTest.kt). That parsing step is exercised implicitly by production use;
 * this test covers the part that is actually testable in isolation: given a real local file,
 * is it inside the upload folder, and if so what workspace path is advertised.
 */
class DocumentAsPromptTransformerTest {

    @Test
    fun `file inside the upload folder resolves to its workspace path`() {
        val file = File(File("/data/user/0/pkg/files/upload"), "abc.png")
        val path = DocumentAsPromptTransformer.resolveWorkspacePathForFile(file)
        assertEquals("/upload/abc.png", path)
    }

    @Test
    fun `file outside the upload folder resolves to null`() {
        val file = File(File("/data/user/0/pkg/files/other"), "abc.png")
        val path = DocumentAsPromptTransformer.resolveWorkspacePathForFile(file)
        assertNull(path)
    }

    @Test
    fun `file with no parent resolves to null`() {
        val file = File("abc.png")
        val path = DocumentAsPromptTransformer.resolveWorkspacePathForFile(file)
        assertNull(path)
    }

    // ---- inline budget (inlineDocuments, fed by fake loaders) ----

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun resetGlobals() {
        DocumentInlineNotices.clear()
        DocumentTokenEstimates.clear()
    }

    private fun doc(url: String, name: String = "a.txt") = UIMessagePart.Document(url = url, fileName = name)

    private fun user(vararg parts: UIMessagePart) = UIMessage(role = MessageRole.USER, parts = parts.toList())

    private fun loader(texts: Map<String, String>) = { document: UIMessagePart.Document ->
        DocumentAsPromptTransformer.LoadedDocument(
            text = texts.getValue(document.url),
            workspacePath = "/upload/${document.fileName}",
        ) { "/upload/.extracted/${document.fileName}.1-2.txt" }
    }

    private fun promptOf(message: UIMessage): String = (message.parts.first() as UIMessagePart.Text).text

    @Test
    fun `small document is inlined whole and unchanged`() {
        val messages = listOf(user(UIMessagePart.Text("看看"), doc("u1")))
        val result = DocumentAsPromptTransformer.inlineDocuments(
            messages, contextLength = 1_000_000, maxOutputTokens = null, load = loader(mapOf("u1" to "hello")),
        )
        assertEquals(
            "<UploadFile name=\"a.txt\" path=\"/upload/a.txt\">\n```\nhello\n```\n</UploadFile>",
            promptOf(result[0]),
        )
        // The original parts (text + document) are kept after the inlined prompt.
        assertEquals(3, result[0].parts.size)
        assertFalse(DocumentInlineNotices.hasPending())
        assertEquals(2L, DocumentTokenEstimates.get("u1")) // "hello" = 5 ASCII chars = 2 tokens
    }

    @Test
    fun `large document is cut to the budget with a notice and the user is told`() {
        // (20_000 - 2_000) * 90% = 16_200; the other content is "看看" (2) + 8 overhead = 10
        // -> 16_190 available, minus the 300-token notice reserve = 15_890 CJK chars shown.
        val messages = listOf(user(UIMessagePart.Text("看看"), doc("u1")))
        val result = DocumentAsPromptTransformer.inlineDocuments(
            messages, contextLength = 20_000, maxOutputTokens = 2_000,
            load = loader(mapOf("u1" to "文".repeat(50_000))),
        )
        val prompt = promptOf(result[0])
        assertTrue(prompt.contains("文".repeat(15_890) + "\n```"))
        assertFalse(prompt.contains("文".repeat(15_891)))
        assertTrue(prompt.contains("[DOCUMENT TRUNCATED]"))
        assertTrue(prompt.contains("15890 of 50000 characters (about 31%)"))
        assertTrue(prompt.contains("/upload/.extracted/a.txt.1-2.txt"))
        assertTrue(prompt.endsWith("</UploadFile>"))
        assertTrue(DocumentTokenEstimates.get("u1")!! < 16_200L)

        val notices = DocumentInlineNotices.takeFor(listOf("u1"))
        assertEquals(listOf(DocumentInlineNotices.Notice("u1", "a.txt", 31)), notices)
        // Announced once only.
        DocumentAsPromptTransformer.inlineDocuments(
            messages, 20_000, 2_000, loader(mapOf("u1" to "文".repeat(50_000))),
        )
        assertTrue(DocumentInlineNotices.takeFor(listOf("u1")).isEmpty())
    }

    @Test
    fun `newest document wins the budget over an older one`() {
        // (30_000 - 2_000) * 90% = 25_200, two empty user messages = 16 -> 25_184 available.
        val messages = listOf(
            user(doc("old", "old.txt")),
            user(doc("new", "new.txt")),
        )
        val result = DocumentAsPromptTransformer.inlineDocuments(
            messages, contextLength = 30_000, maxOutputTokens = 2_000,
            load = loader(mapOf("old" to "旧".repeat(15_000), "new" to "新".repeat(15_000))),
        )
        assertFalse(promptOf(result[1]).contains("[DOCUMENT TRUNCATED]"))
        assertTrue(promptOf(result[1]).contains("新".repeat(15_000)))
        assertTrue(promptOf(result[0]).contains("[DOCUMENT TRUNCATED]"))
        // 25_184 - 15_000 - 300 = 9_884 characters of the older one.
        assertTrue(promptOf(result[0]).contains("9884 of 15000 characters"))
    }

    @Test
    fun `unknown context window uses the 128k default`() {
        // Default budget: (128_000 - 16_000) * 90% = 100_800 minus 8 for the message.
        val fits = DocumentAsPromptTransformer.inlineDocuments(
            listOf(user(doc("u1"))), contextLength = null, maxOutputTokens = null,
            load = loader(mapOf("u1" to "字".repeat(90_000))),
        )
        assertFalse(promptOf(fits[0]).contains("[DOCUMENT TRUNCATED]"))
        val tooBig = DocumentAsPromptTransformer.inlineDocuments(
            listOf(user(doc("u1"))), contextLength = null, maxOutputTokens = null,
            load = loader(mapOf("u1" to "字".repeat(120_000))),
        )
        assertTrue(promptOf(tooBig[0]).contains("[DOCUMENT TRUNCATED]"))
    }

    @Test
    fun `messages without documents are returned untouched`() {
        val messages = listOf(user(UIMessagePart.Text("hi")))
        val result = DocumentAsPromptTransformer.inlineDocuments(messages, 1_000, null, loader(emptyMap()))
        assertEquals(messages, result)
    }

    @Test
    fun `plain text upload gets a full-text copy only when it is truncated`() {
        val upload = tmp.newFolder("upload")
        val source = File(upload, "big.txt").apply { writeText("line\n".repeat(10)) }
        val loaded = DocumentAsPromptTransformer.loadDocumentFile(source, "big.txt", "text/plain")
        assertEquals("line\n".repeat(10), loaded.text)
        assertEquals("/upload/big.txt", loaded.workspacePath)
        assertFalse(File(upload, ".extracted").exists())
        val path = loaded.fullTextPath()
        assertNotNull(path)
        assertTrue(path!!.startsWith("/upload/.extracted/big.txt."))
        assertTrue(File(upload, ".extracted").listFiles()!!.single().readText() == "line\n".repeat(10))
    }
}
