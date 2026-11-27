package com.clma.app.data.ai.tools

import com.clma.ai.core.InputSchema
import com.clma.ai.core.MessageRole
import com.clma.ai.core.Tool
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import com.clma.app.data.ai.transformers.DocumentAsPromptTransformer
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.URI

/** References identify a document part in a stored message, never an arbitrary path supplied by the model. */
internal fun conversationAttachmentRef(
    message: UIMessage,
    partIndex: Int,
): String = "${message.id}:$partIndex"

private data class ConversationAttachmentEntry(
    val attachmentRef: String,
    val date: String,
    val text: String,
    val fileName: String,
    val mime: String,
    val path: String,
)

/**
 * Lists document attachments in the current conversation's selected branch, including messages
 * already folded into a compaction summary. The returned `path` is the stable `/upload/...` path
 * accepted by `read_attachment`; this tool never returns file content itself.
 */
fun createConversationAttachmentTool(
    messages: suspend () -> List<UIMessage>,
): Tool =
    Tool(
        name = "conversation_attachment",
        description =
            "List user-uploaded document attachments in the CURRENT conversation's selected branch, " +
                "including messages folded into a compaction summary. Returns up to 20 entries per page " +
                "with stable attachment_ref, file_name, mime, date, nearby text, and the exact /upload/... " +
                "path to pass to read_attachment. Use attachment_ref to resolve one listed entry without " +
                "paging. Attachment text is NOT returned here: use read_attachment for bounded paging. " +
                "No arbitrary paths or other conversations.",
        parameters = {
            InputSchema.Obj(
                properties =
                    buildJsonObject {
                        put(
                            "offset",
                            buildJsonObject {
                                put("type", "integer")
                                put("minimum", 0)
                                put("description", "Listing offset, default 0")
                            },
                        )
                        put(
                            "attachment_ref",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "Stable ref returned by this tool; resolves one listed entry")
                            },
                        )
                    },
            )
        },
        execute = { input ->
            val args = input.jsonObject
            val attachmentRef = args["attachment_ref"]?.jsonPrimitive?.contentOrNull
            val entries = collectConversationAttachments(messages())
            val response =
                buildJsonObject {
                    if (attachmentRef != null) {
                        val entry = entries.firstOrNull { it.attachmentRef == attachmentRef }
                        if (entry == null) {
                            put("error", "attachment_not_found")
                            put("detail", "attachment_ref must come from conversation_attachment in this conversation")
                        } else {
                            put("attachment", attachmentJson(entry))
                        }
                    } else {
                        val offset = (args["offset"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0)
                        val page = entries.drop(offset).take(20)
                        put(
                            "attachments",
                            buildJsonArray {
                                page.forEach { entry -> add(attachmentJson(entry)) }
                            },
                        )
                        if (entries.size.toLong() > offset.toLong() + page.size.toLong()) {
                            put("next_offset", offset + page.size)
                        }
                    }
                }
            listOf(UIMessagePart.Text(response.toString()))
        },
    )

private fun collectConversationAttachments(messages: List<UIMessage>): List<ConversationAttachmentEntry> =
    messages
        .filter { it.role == MessageRole.USER && !it.isSynthetic }
        .flatMap { message ->
            message.parts.mapIndexedNotNull { index, part ->
                if (part !is UIMessagePart.Document) return@mapIndexedNotNull null
                val path = attachmentPath(part.url) ?: return@mapIndexedNotNull null
                ConversationAttachmentEntry(
                    attachmentRef = conversationAttachmentRef(message, index),
                    date = message.createdAt.toString(),
                    text = message.toText().take(240),
                    fileName = part.fileName,
                    mime = part.mime,
                    path = path,
                )
            }
        }

private fun attachmentJson(entry: ConversationAttachmentEntry) =
    buildJsonObject {
        put("attachment_ref", entry.attachmentRef)
        put("date", entry.date)
        put("text", entry.text)
        put("file_name", entry.fileName)
        put("mime", entry.mime)
        put("path", entry.path)
    }

/** Only local uploads are listable; the returned path is always a bounded `/upload/<name>`. */
private fun attachmentPath(url: String): String? =
    runCatching { File(URI.create(url)) }
        .getOrNull()
        ?.let(DocumentAsPromptTransformer::resolveWorkspacePathForFile)
