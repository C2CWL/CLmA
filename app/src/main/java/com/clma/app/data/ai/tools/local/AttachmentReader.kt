package com.clma.app.data.ai.tools.local

import java.io.File

internal fun resolveAttachmentFile(
    uploadDir: File,
    path: String,
): File {
    require(path.startsWith("/upload/")) { "Use the /upload/ path supplied with the attachment" }
    val root = uploadDir.canonicalFile
    val file = File(root, path.removePrefix("/upload/")).canonicalFile
    require(file.path.startsWith(root.path + File.separator)) { "Attachment path escapes /upload" }
    require(file.isFile) { "Attachment not found" }
    return file
}

internal data class AttachmentPage(
    val text: String,
    val nextOffset: Int?,
    val nextColumn: Int?,
)

/** Bounded memory even for extracted documents containing a single enormous line. */
internal fun readAttachmentPage(
    file: File,
    offset: Int,
    column: Int,
    limit: Int,
    maxChars: Int = 100_000,
): AttachmentPage =
    file.bufferedReader(Charsets.UTF_8).use { reader ->
        require(offset >= 1 && column >= 0 && limit >= 1 && maxChars >= 1)
        var line = 1
        var position = 0
        while (line < offset) {
            val ch = reader.read()
            if (ch < 0) return@use AttachmentPage("", null, null)
            if (ch == '\n'.code) line++
        }
        while (position < column) {
            val ch = reader.read()
            require(ch != '\n'.code) { "column exceeds the requested line" }
            if (ch < 0) return@use AttachmentPage("", null, null)
            position++
        }
        val text = StringBuilder()
        var lines = 0
        while (text.length < maxChars && lines < limit) {
            val ch = reader.read()
            if (ch < 0) return@use AttachmentPage(text.toString(), null, null)
            text.append(ch.toChar())
            if (ch == '\n'.code) {
                line++
                position = 0
                lines++
            } else {
                position++
            }
        }
        val more = reader.read() >= 0
        AttachmentPage(text.toString(), line.takeIf { more }, position.takeIf { more })
    }
