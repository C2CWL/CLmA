package me.rerere.rikkahub.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.context.Navigator
import kotlin.uuid.Uuid

private const val TAG = "ChatUtil"

fun navigateToChatPage(
    navigator: Navigator,
    chatId: Uuid = Uuid.random(),
    initText: String? = null,
    initFiles: List<Uri> = emptyList(),
    nodeId: Uuid? = null,
) {
    Log.i(TAG, "navigateToChatPage: navigate to $chatId")
    navigator.clearAndNavigate(
        Screen.Chat(
            id = chatId.toString(),
            text = initText,
            files = initFiles.map { it.toString() },
            nodeId = nodeId?.toString(),
        )
    )
}

fun Context.copyMessageToClipboard(message: UIMessage) {
    this.writeClipboardText(message.toText())
}

/**
 * MIME types that should be inlined as text (or parsed) when building the model prompt.
 * Upload itself no longer rejects other types — binary files are still attached and exposed
 * under `/upload` for workspace tools.
 */
private val INLINE_TEXT_MIME_TYPES = setOf(
    "text/plain", "text/html", "text/css", "text/javascript", "text/csv", "text/xml",
    "application/json", "application/javascript", "application/pdf",
    "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-powerpoint",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/epub+zip"
)

private val INLINE_TEXT_FILE_EXTENSIONS = setOf(
    "txt", "md", "csv", "json", "js", "jsx", "mjs", "cjs",
    "html", "css", "vue", "svelte", "xml", "agc",
    "py", "rb", "lua", "luau", "sql", "java", "kt", "ts", "tsx",
    "dart", "php", "swift", "go",
    "bat", "cmd", "ps1", "psm1", "sh", "bash", "zsh", "fish",
    "c", "h", "cpp", "cc", "cxx", "hpp", "hh", "hxx",
    "rs", "cs", "markdown", "mdx",
    "toml", "ini", "env", "gradle", "kts", "properties",
    "proto", "graphql", "gql", "yml", "yaml"
)

/**
 * Whether this attachment should be treated as an inlinable text/document for prompt extraction.
 * Not a gate on upload — any file may be attached.
 */
fun isAllowedFileType(fileName: String, mime: String): Boolean {
    if (mime in INLINE_TEXT_MIME_TYPES || mime.startsWith("text/")) return true
    val extension = fileName.substringAfterLast('.', "").lowercase()
    return extension in INLINE_TEXT_FILE_EXTENSIONS
}

/** Guess a MIME from the file name when the provider returns null/empty. */
fun guessMimeFromFileName(fileName: String): String? {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    if (ext.isEmpty() || ext == fileName.lowercase()) return null
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
}

/**
 * Classify a picked attachment into the chat part kind used by the composer.
 * Media goes to Image/Video/Audio; everything else is a Document (including apk/zip/bin).
 */
enum class ChatAttachmentKind {
    IMAGE,
    VIDEO,
    AUDIO,
    DOCUMENT,
}

fun classifyChatAttachment(fileName: String, mime: String): ChatAttachmentKind {
    val normalized = mime.lowercase().substringBefore(';').trim()
    when {
        normalized.startsWith("image/") -> return ChatAttachmentKind.IMAGE
        normalized.startsWith("video/") -> return ChatAttachmentKind.VIDEO
        normalized.startsWith("audio/") -> return ChatAttachmentKind.AUDIO
    }
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "png", "jpg", "jpeg", "gif", "webp", "heic", "heif", "avif", "bmp", "svg" ->
            ChatAttachmentKind.IMAGE
        "mp4", "mkv", "webm", "mov", "m4v", "avi", "3gp" ->
            ChatAttachmentKind.VIDEO
        "mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "amr" ->
            ChatAttachmentKind.AUDIO
        else -> ChatAttachmentKind.DOCUMENT
    }
}

fun ChatAttachmentKind.toMessagePart(
    localUri: String,
    fileName: String,
    mime: String,
): UIMessagePart = when (this) {
    ChatAttachmentKind.IMAGE -> UIMessagePart.Image(url = localUri)
    ChatAttachmentKind.VIDEO -> UIMessagePart.Video(url = localUri)
    ChatAttachmentKind.AUDIO -> UIMessagePart.Audio(url = localUri)
    ChatAttachmentKind.DOCUMENT -> UIMessagePart.Document(
        url = localUri,
        fileName = fileName,
        mime = mime,
    )
}
