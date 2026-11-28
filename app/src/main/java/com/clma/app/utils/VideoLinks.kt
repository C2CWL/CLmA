package com.clma.app.utils

import java.net.URI

internal object VideoLinks {
    val extensions = setOf("mp4", "m4v", "webm", "mov", "mkv", "3gp", "avi", "mpeg", "mpg", "wmv", "flv")
    private val urls = Regex("""https?://[^\s<>"，。！？、）]+""", RegexOption.IGNORE_CASE)
    private val videoIntent = Regex("""\b(video|clip|footage)\b|视频|影片|录像|录屏""", RegexOption.IGNORE_CASE)
    private val platforms = setOf("bilibili.com", "b23.tv", "douyin.com", "iesdouyin.com",
        "tiktok.com", "youtube.com", "youtu.be", "kuaishou.com", "v.qq.com", "ixigua.com")

    data class Link(val url: String, val expectedVideo: Boolean)

    fun find(text: String): List<Link> {
        val explicit = videoIntent.containsMatchIn(text.replace(urls, " "))
        return urls.findAll(text)
            .map { it.value.trimEnd('.', ',', ')', ']', '}', '!', ';') }
            .mapNotNull { url ->
                val uri = runCatching { URI(url) }.getOrNull() ?: return@mapNotNull null
                val host = uri.host?.lowercase() ?: return@mapNotNull null
                val expectedVideo = explicit || isPlatform(host) || extension(uri.path.orEmpty()) in extensions
                Link(url, expectedVideo).takeIf { expectedVideo || text.trim() == url }
            }
            .distinct()
            .take(2)
            .toList()
    }

    fun isPlatform(host: String): Boolean =
        platforms.any { host == it || host.endsWith(".$it") }

    fun extension(path: String): String = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
}
