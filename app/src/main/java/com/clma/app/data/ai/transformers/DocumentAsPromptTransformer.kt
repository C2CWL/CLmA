package com.clma.app.data.ai.transformers

import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import com.clma.app.data.ai.ContextBudgetPlanner
import com.clma.app.data.ai.DocumentTokenEstimates
import com.clma.app.data.ai.document.BoundedTextReader
import com.clma.app.data.ai.document.DocumentFormat
import com.clma.app.data.ai.document.DocumentInlineBudget
import com.clma.app.data.ai.document.DocumentInlineNotices
import com.clma.app.data.ai.document.DocumentTextExtractor
import com.clma.app.data.ai.document.ExtractedTextCache
import com.clma.app.data.datastore.getEffectiveContextLength
import java.io.File

/**
 * Inlines attached documents into the prompt as `<UploadFile>` text.
 *
 * A document that fits in the model's context (after the rest of the request and the reserved
 * output) is inlined whole, as before. One that does not fit is cut to the head that fits and
 * followed by a notice telling the model how much it sees and where the full extracted text is,
 * so it can read the rest with workspace_read_file offset/limit. The user is told too (see
 * [DocumentInlineNotices]). Budget arithmetic: [DocumentInlineBudget].
 */
object DocumentAsPromptTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return withContext(Dispatchers.IO) {
            inlineDocuments(
                messages = messages,
                contextLength = ctx.settings.getEffectiveContextLength(ctx.model),
                maxOutputTokens = ctx.assistant.maxTokens,
                load = { document -> loadDocument(document, Int.MAX_VALUE) },
                imageWorkspacePath = ::resolveWorkspacePath,
                loadBounded = ::loadDocument,
            )
        }
    }

    /** A document's text plus where the model can find it in the workspace. */
    internal class LoadedDocument(
        val text: String,
        /** Workspace path of the original upload (`/upload/<name>`), if any. */
        val workspacePath: String?,
        /**
         * Set when [text] is only the head of a larger document (bounded read): the size of the
         * whole text. Null when [text] is the whole document.
         */
        val fullSize: BoundedTextReader.FullSize? = null,
        /** Workspace path of the full extracted text, created on demand; null when unavailable. */
        val fullTextPath: () -> String?,
    ) {
        val totalTokens: Long get() = fullSize?.tokens ?: DocumentInlineBudget.estimateTokens(text)
        val totalChars: Int get() = fullSize?.chars ?: text.length
        val totalLines: Int get() = fullSize?.lines ?: DocumentInlineBudget.lineCount(text)
    }

    /**
     * Characters read from a document at most: no single document can be given more than the
     * whole document budget, and the head cut counts at most 3 characters per token. The margin
     * covers the minimum preview and rounding.
     */
    internal fun maxReadChars(availableTokens: Long): Int =
        (availableTokens.coerceAtLeast(0L) * 3L + READ_MARGIN_CHARS)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

    private const val READ_MARGIN_CHARS = 16_384L

    /**
     * Core of [transform], free of Android types so it can be unit-tested with fake loaders.
     * Message layout is unchanged: each document's `<UploadFile>` text is inserted at the front
     * of its message's parts (the Document part itself stays).
     */
    internal fun inlineDocuments(
        messages: List<UIMessage>,
        contextLength: Int?,
        maxOutputTokens: Int?,
        load: (UIMessagePart.Document) -> LoadedDocument,
        imageWorkspacePath: (String) -> String? = { null },
        /** When set, documents are read through this with a character cap (see [maxReadChars]). */
        loadBounded: ((UIMessagePart.Document, Int) -> LoadedDocument)? = null,
    ): List<UIMessage> {
        // Every document in request order, loaded once.
        val refs = messages.flatMapIndexed { messageIndex, message ->
            message.parts.filterIsInstance<UIMessagePart.Document>().map { messageIndex to it }
        }

        val prompts = if (refs.isEmpty()) {
            emptyList()
        } else {
            val withoutDocuments = messages.map { message ->
                message.copy(parts = message.parts.filterNot { it is UIMessagePart.Document })
            }
            val available = DocumentInlineBudget.availableTokens(
                contextLength = contextLength,
                maxOutputTokens = maxOutputTokens,
                otherTokens = ContextBudgetPlanner.estimateContextTokens(withoutDocuments).toLong(),
            )
            val readCap = maxReadChars(available)
            val loaded = refs.map { (_, document) ->
                loadBounded?.invoke(document, readCap) ?: load(document)
            }
            val sizes = loaded.map { it.totalTokens }
            // Newest document first: the one the user just sent wins over older ones.
            val newestFirst = refs.indices.reversed().toList()
            val allocations = DocumentInlineBudget.allocate(newestFirst.map { sizes[it] }, available)
            val allocationByIndex = newestFirst.zip(allocations).toMap()
            refs.indices.map { index ->
                buildPrompt(
                    document = refs[index].second,
                    loaded = loaded[index],
                    totalTokens = sizes[index],
                    allocation = allocationByIndex.getValue(index),
                )
            }
        }

        val promptsByMessage = refs.indices.groupBy({ refs[it].first }, { prompts[it] })
        return messages.mapIndexed { messageIndex, message ->
            val documentPrompts = promptsByMessage[messageIndex].orEmpty()
            val imagePaths = message.parts.filterIsInstance<UIMessagePart.Image>()
                .mapNotNull { imageWorkspacePath(it.url) }
            if (documentPrompts.isEmpty() && imagePaths.isEmpty()) return@mapIndexed message
            message.copy(
                parts = message.parts.toMutableList().apply {
                    documentPrompts.forEach { add(0, UIMessagePart.Text(it)) }
                    imagePaths.forEach { path ->
                        add(0, UIMessagePart.Text("<UploadFile name=\"${File(path).name}\" path=\"$path\" />"))
                    }
                }
            )
        }
    }

    private fun buildPrompt(
        document: UIMessagePart.Document,
        loaded: LoadedDocument,
        totalTokens: Long,
        allocation: DocumentInlineBudget.Allocation,
    ): String {
        val pathAttr = loaded.workspacePath?.let { " path=\"$it\"" } ?: ""
        val content: String
        var notice: String? = null
        if (allocation.fitsWhole && loaded.fullSize == null) {
            content = loaded.text
            DocumentTokenEstimates.record(document.url, totalTokens)
        } else {
            val head = DocumentInlineBudget.headWithinTokens(loaded.text, allocation.tokens)
            content = head.text
            notice = DocumentInlineBudget.truncationNotice(
                shownChars = head.chars,
                totalChars = loaded.totalChars,
                totalTokens = totalTokens,
                totalLines = loaded.totalLines,
                head = head,
                fullTextPath = loaded.fullTextPath(),
            )
            DocumentTokenEstimates.record(
                document.url,
                DocumentInlineBudget.estimateTokens(head.text) + DocumentInlineBudget.estimateTokens(notice),
            )
            DocumentInlineNotices.report(
                DocumentInlineNotices.Notice(
                    url = document.url,
                    fileName = document.fileName,
                    shownPercent = DocumentInlineBudget.shownPercent(head.chars, loaded.totalChars),
                )
            )
        }
        return buildString {
            append("<UploadFile name=\"${document.fileName}\"$pathAttr>\n")
            append("```\n")
            append(content)
            append("\n```\n")
            if (notice != null) append(notice).append('\n')
            append("</UploadFile>")
        }
    }

    /** Head + full-size statistics of [source]; null when it cannot be read. */
    private fun boundedLoad(
        source: File,
        charset: java.nio.charset.Charset,
        bomBytes: Int,
        maxChars: Int,
        workspacePath: String?,
        fullTextPath: () -> String?,
    ): LoadedDocument? = runCatching {
        val result = BoundedTextReader.read(source, charset, bomBytes, maxChars)
        LoadedDocument(
            text = result.head,
            workspacePath = workspacePath,
            fullTextPath = fullTextPath,
            fullSize = result.full.takeUnless { result.isComplete },
        )
    }.getOrNull()

    // 上传文件保存在 filesDir/upload 下, 该目录通过 proot 挂载到 workspace 的 /upload
    // 返回文件在 workspace 内的绝对路径, 便于 AI 用 workspace 工具直接读取原始文件
    private fun resolveWorkspacePath(url: String): String? {
        val file = runCatching { url.toUri().toFile() }.getOrNull() ?: return null
        return resolveWorkspacePathForFile(file)
    }

    // 纯函数, 不依赖 android.net.Uri, 以便单元测试覆盖 upload 目录网关判断
    internal fun resolveWorkspacePathForFile(file: File): String? {
        if (file.parentFile?.name != "upload") return null
        return "/upload/${file.name}"
    }

    private fun loadDocument(document: UIMessagePart.Document, maxChars: Int): LoadedDocument {
        val file = runCatching { document.url.toUri().toFile() }.getOrNull()
            ?: return LoadedDocument("[ERROR, invalid file uri: ${document.fileName}]", null) { null }
        return loadDocumentFile(file, document.fileName, document.mime, maxChars)
    }

    /**
     * Reads [file] through the extracted-text cache. Parsed formats (PDF/DOCX/PPTX/EPUB) are
     * cached right away so they are not re-parsed on every request; plain text is copied into
     * the cache only when the model needs a file to page through (i.e. after truncation).
     */
    internal fun loadDocumentFile(
        file: File,
        fileName: String,
        mime: String,
        /** Read at most this many characters (the rest is only counted); see [maxReadChars]. */
        maxChars: Int = Int.MAX_VALUE,
    ): LoadedDocument {
        val workspacePath = resolveWorkspacePathForFile(file)
        if (!file.exists() || !file.isFile) {
            return LoadedDocument("[ERROR, file not found: $fileName]", workspacePath) { null }
        }
        val cache = ExtractedTextCache.cacheFileFor(file)?.takeIf { it.isFile }
        // UTF-8 never has fewer bytes than characters: a cache within the cap is read whole.
        if (cache != null && cache.length() > maxChars) {
            boundedLoad(cache, Charsets.UTF_8, 0, maxChars, workspacePath) {
                ExtractedTextCache.workspacePathOf(cache)
            }?.let { return it }
        }
        ExtractedTextCache.read(file)?.let { cached ->
            return LoadedDocument(cached, workspacePath) {
                ExtractedTextCache.cacheFileFor(file)?.let(ExtractedTextCache::workspacePathOf)
            }
        }
        // A large plain-text file: never readBytes() it. Copy it into the cache as a stream (the
        // model pages through that copy anyway) and read only the head that can be inlined.
        if (file.length() > maxChars &&
            DocumentTextExtractor.detectFormat(file, fileName, mime) == DocumentFormat.TEXT
        ) {
            val detected = BoundedTextReader.detectCharset(file)
            val streamedCache = ExtractedTextCache.writeStreaming(file, detected.charset, detected.bomBytes)
            val loaded = if (streamedCache != null) {
                boundedLoad(streamedCache, Charsets.UTF_8, 0, maxChars, workspacePath) {
                    ExtractedTextCache.workspacePathOf(streamedCache)
                }
            } else {
                boundedLoad(file, detected.charset, detected.bomBytes, maxChars, workspacePath) { null }
            }
            loaded?.let { return it }
        }
        val extraction = DocumentTextExtractor.extract(file, fileName, mime)
        val cacheFile = if (extraction.cacheable) ExtractedTextCache.write(file, extraction.text) else null
        // Parsed formats (PDF/DOCX/...) are extracted in memory once; keep only the head.
        if (extraction.text.length > maxChars) {
            return LoadedDocument(
                text = extraction.text.substring(0, maxChars),
                workspacePath = workspacePath,
                fullTextPath = { cacheFile?.let(ExtractedTextCache::workspacePathOf) },
                fullSize = BoundedTextReader.FullSize(
                    chars = extraction.text.length,
                    tokens = DocumentInlineBudget.estimateTokens(extraction.text),
                    lines = DocumentInlineBudget.lineCount(extraction.text),
                ),
            )
        }
        return LoadedDocument(extraction.text, workspacePath) {
            cacheFile?.let(ExtractedTextCache::workspacePathOf)
                ?: if (extraction.format == DocumentFormat.TEXT) {
                    ExtractedTextCache.write(file, extraction.text)?.let(ExtractedTextCache::workspacePathOf)
                } else {
                    null
                }
        }
    }
}
