package com.clma.app.data.ai.transformers

import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import com.clma.app.data.ai.ContextBudgetPlanner
import com.clma.app.data.ai.DocumentTokenEstimates
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
                load = ::loadDocument,
                imageWorkspacePath = ::resolveWorkspacePath,
            )
        }
    }

    /** A document's text plus where the model can find it in the workspace. */
    internal class LoadedDocument(
        val text: String,
        /** Workspace path of the original upload (`/upload/<name>`), if any. */
        val workspacePath: String?,
        /** Workspace path of the full extracted text, created on demand; null when unavailable. */
        val fullTextPath: () -> String?,
    )

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
    ): List<UIMessage> {
        // Every document in request order, loaded once.
        val refs = messages.flatMapIndexed { messageIndex, message ->
            message.parts.filterIsInstance<UIMessagePart.Document>().map { messageIndex to it }
        }
        val loaded = refs.map { (_, document) -> load(document) }

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
            val sizes = loaded.map { DocumentInlineBudget.estimateTokens(it.text) }
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
        if (allocation.fitsWhole) {
            content = loaded.text
            DocumentTokenEstimates.record(document.url, totalTokens)
        } else {
            val head = DocumentInlineBudget.headWithinTokens(loaded.text, allocation.tokens)
            content = head.text
            notice = DocumentInlineBudget.truncationNotice(
                shownChars = head.chars,
                totalChars = loaded.text.length,
                totalTokens = totalTokens,
                totalLines = DocumentInlineBudget.lineCount(loaded.text),
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
                    shownPercent = DocumentInlineBudget.shownPercent(head.chars, loaded.text.length),
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

    private fun loadDocument(document: UIMessagePart.Document): LoadedDocument {
        val file = runCatching { document.url.toUri().toFile() }.getOrNull()
            ?: return LoadedDocument("[ERROR, invalid file uri: ${document.fileName}]", null) { null }
        return loadDocumentFile(file, document.fileName, document.mime)
    }

    /**
     * Reads [file] through the extracted-text cache. Parsed formats (PDF/DOCX/PPTX/EPUB) are
     * cached right away so they are not re-parsed on every request; plain text is copied into
     * the cache only when the model needs a file to page through (i.e. after truncation).
     */
    internal fun loadDocumentFile(file: File, fileName: String, mime: String): LoadedDocument {
        val workspacePath = resolveWorkspacePathForFile(file)
        if (!file.exists() || !file.isFile) {
            return LoadedDocument("[ERROR, file not found: $fileName]", workspacePath) { null }
        }
        ExtractedTextCache.read(file)?.let { cached ->
            return LoadedDocument(cached, workspacePath) {
                ExtractedTextCache.cacheFileFor(file)?.let(ExtractedTextCache::workspacePathOf)
            }
        }
        val extraction = DocumentTextExtractor.extract(file, fileName, mime)
        val cacheFile = if (extraction.cacheable) ExtractedTextCache.write(file, extraction.text) else null
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
