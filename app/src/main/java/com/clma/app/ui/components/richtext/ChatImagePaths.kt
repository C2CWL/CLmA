package com.clma.app.ui.components.richtext

import java.net.URLDecoder

/**
 * 把聊天里图片地址中「指向工作区 Rootfs 内部」的写法识别出来。
 *
 * AI 在工作区里用 workspace_shell 生成图片后, 会在回复的 Markdown 里写
 * `![](/workspace/a.png)`、`![](a.png)`、`![](file:///workspace/a.png)` 或
 * `![](sandbox:/workspace/a.png)` 这类 **Rootfs 内** 的路径。这些路径在 Android 侧并不存在
 * (真实文件在 `filesDir/workspaces/<root>/files/` 下), 直接交给 Coil 必然加载失败,
 * 界面上只剩一块透明的空白。
 *
 * 这里只做纯字符串判断 (不依赖 android.net.Uri, 便于单元测试), 返回 Rootfs 内的绝对路径;
 * 真正映射到宿主机文件交给 WorkspaceManager.resolveRootfsFileOrNull。
 */
internal object ChatImagePaths {
    /** 网络地址、data: 内联图、content:// 等, 原样交给 Coil */
    private val SCHEME_REGEX = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")

    /** 本来就是 Android 宿主机上的绝对路径, 原样交给 Coil */
    private val HOST_PATH_PREFIXES = listOf("/data/", "/storage/", "/sdcard/", "/mnt/")

    /**
     * 若 [raw] 是工作区 Rootfs 内的图片路径, 返回规范化后的 Rootfs 绝对路径 (如 `/workspace/a.png`);
     * 否则 (网络地址 / data: / content: / 宿主机路径 / `~` 路径 / 空) 返回 null, 调用方按原样加载。
     */
    fun workspacePathOf(raw: String?): String? {
        if (raw == null) return null
        val url = raw.trim().removeSurrounding("<", ">").trim()
        if (url.isEmpty() || url.startsWith("#") || url.startsWith("~")) return null

        val scheme = SCHEME_REGEX.find(url)?.groupValues?.get(1)?.lowercase()
        var path = when (scheme) {
            null -> url
            "file" -> fileUrlPath(url.substring("file:".length))
            "sandbox", "workspace" -> url.substringAfter(':').let { rest ->
                // sandbox:/workspace/a.png 与 workspace:a.png 两种写法都接受
                if (rest.startsWith("//")) "/" + rest.trimStart('/') else rest
            }
            else -> return null
        }
        if (scheme != null) path = path.substringBefore('?').substringBefore('#')
        if (path.contains('%')) {
            path = runCatching { URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") }.getOrDefault(path)
        }
        path = path.replace('\\', '/').trim()
        if (path.isEmpty() || path.startsWith("~")) return null

        if (path.startsWith("/")) {
            if (HOST_PATH_PREFIXES.any { path.startsWith(it) } || path == "/sdcard") return null
            return collapseSlashes(path)
        }
        // 相对路径: 相对于工作区文件区 (workspace_shell 的默认 cwd 就是 /workspace)
        return collapseSlashes("/workspace/" + path.removePrefix("./"))
    }

    private fun fileUrlPath(afterScheme: String): String {
        // file:///a、file:/a、file://localhost/a 都映射到 /a
        if (!afterScheme.startsWith("/")) return afterScheme
        val noSlashes = afterScheme.trimStart('/')
        val withoutHost = if (afterScheme.startsWith("//") && noSlashes.startsWith("localhost/")) {
            noSlashes.removePrefix("localhost")
        } else {
            "/$noSlashes"
        }
        return withoutHost
    }

    private fun collapseSlashes(path: String): String = path.replace(Regex("/{2,}"), "/")
}
