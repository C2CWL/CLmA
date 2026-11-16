package com.clma.app.data.ai.tools.local

import android.content.Context
import android.net.Uri
import java.io.File

/** Resolve aliases only at the Android boundary; never bypass OS grants or sandbox guards. */
internal object LocalMediaAccess {
    const val RECOVERY = "Reattach the original media in this chat or select it with Android's file picker to grant access. " +
        "Do not retry unchanged paths, copy into Termux/private app directories, or convert media to base64 text. " +
        "Use the exact attachment path from this chat, including /upload or the app files directory."

    fun resolve(context: Context, raw: String): String {
        require(raw.isNotBlank() && !raw.contains('\u0000')) { "Invalid media path" }
        if (ContentUriSafetyGuard.isContentUri(raw)) {
            ContentUriSafetyGuard.check(raw)?.let { error(it.detail) }
            requireNotNull(context.contentResolver.openInputStream(Uri.parse(raw))) {
                "Cannot open media URI"
            }.use { }
            return raw
        }
        val uri = Uri.parse(raw)
        require(uri.scheme == null || uri.scheme == "file") { "Use local attachments, not remote URLs" }
        require(uri.scheme != "file" || uri.authority.isNullOrEmpty()) { "File URI must not have a host" }
        val path = if (uri.scheme == "file") requireNotNull(uri.path) else raw
        val expanded = AgentWorkspace.expand(path)
        val uploadRoot = File(context.filesDir, "upload").canonicalFile
        val file = if (expanded.startsWith("/upload/")) {
            resolveAttachmentFile(uploadRoot, expanded)
        } else {
            require(File(expanded).isAbsolute) { "Use an absolute media path" }
            val canonical = File(expanded).canonicalFile
            if (canonical.path == uploadRoot.path || canonical.path.startsWith(uploadRoot.path + File.separator)) {
                resolveAttachmentFile(uploadRoot, "/upload/" + canonical.path.removePrefix(uploadRoot.path).removePrefix("/"))
            } else canonical
        }
        PathSafetyGuard.check(file.path)?.let { error(it.detail) }
        require(file.isFile && file.canRead()) { "Media file is missing or not readable by CLmA" }
        file.inputStream().use { }
        return file.absolutePath
    }
}
