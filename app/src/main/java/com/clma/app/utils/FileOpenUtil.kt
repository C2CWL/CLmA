package com.clma.app.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/**
 * 聊天里点开附件的统一入口。
 *
 * 为什么需要它：工作区 / 附件都落在 App 私有目录（`/data/data/<pkg>/files/...`），
 * 直接把 `file://` 路径丢给 `ACTION_VIEW`，Android 7+ 会抛 FileUriExposedException，
 * 即使不抛，目标 App 也读不到沙箱里的文件。所以本地文件必须先转成 FileProvider 的
 * `content://` URI 并带上读取授权，再交给系统选择器。
 *
 * 所有函数都返回 [Result]，调用方统一弹提示，避免 `startActivity` 的异常直接抛到主线程闪退。
 */
object FileOpenUtil {

    /** 按扩展名猜 MIME；[override] 为空或过于笼统时以扩展名为准 */
    fun guessMime(file: File, override: String? = null): String {
        val normalized = override?.trim()?.substringBefore(';')?.lowercase()
        if (!normalized.isNullOrEmpty() && normalized != "*/*" && normalized != "application/octet-stream") {
            return normalized
        }
        val ext = file.extension.lowercase()
        if (ext.isEmpty()) return "*/*"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }

    /** 打开宿主机上的本地文件（私有目录文件自动走 FileProvider） */
    fun open(context: Context, file: File, mimeOverride: String? = null): Result<Unit> = runCatching {
        check(file.exists()) { "文件不存在：${file.absolutePath}" }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        launch(context, uri, guessMime(file, mimeOverride))
    }

    /**
     * 打开任意 URI：
     * - `content://`：直接 ACTION_VIEW（DocumentsProvider 已保证可读），MIME 取 provider 的
     * - `file://`：取出路径后走 [open]，即 FileProvider
     * - 其他（http/https/mailto…）：原样交给系统
     */
    fun openUri(context: Context, uri: Uri, mimeOverride: String? = null): Result<Unit> = runCatching {
        when (uri.scheme?.lowercase()) {
            "content" -> {
                val mime = mimeOverride?.trim()?.takeIf { it.isNotEmpty() }
                    ?: context.contentResolver.getType(uri)
                    ?: "*/*"
                launch(context, uri, mime)
            }

            "file" -> open(context, File(uri.path ?: error("无效的文件路径")), mimeOverride).getOrThrow()

            else -> launch(context, uri, mimeOverride?.trim()?.takeIf { it.isNotEmpty() } ?: "*/*")
        }
    }

    private fun launch(context: Context, uri: Uri, mime: String) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, null).apply {
            // createChooser 不会自动继承内层 intent 的授权标记，需在 chooser 上也声明一次
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(chooser)
        } catch (e: ActivityNotFoundException) {
            throw IllegalStateException("没有可以打开 $mime 的应用", e)
        }
    }
}
