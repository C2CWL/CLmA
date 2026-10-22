package me.rerere.rikkahub.browser

/**
 * Search-engine catalogue + address-bar input normalisation for the in-app browser.
 *
 * Pure Kotlin (no Android imports) so the rules stay JVM-testable. The engine id is
 * persisted via [BrowserPreferences]; the address bar calls [normalizeBrowserQuery]
 * with the persisted id so the Settings → Browser dropdown actually does something
 * (it was a no-op stub until now).
 */
data class BrowserSearchEngine(
    val id: String,
    /** Display name — engine brands are proper nouns and stay unlocalized. */
    val label: String,
    /** Query URL template; `%s` is replaced with the URL-encoded query. */
    val queryTemplate: String,
) {
    fun searchUrl(query: String): String =
        queryTemplate.replace("%s", java.net.URLEncoder.encode(query, "UTF-8"))
}

object BrowserSearchEngines {
    val DUCKDUCKGO = BrowserSearchEngine("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q=%s")
    val GOOGLE = BrowserSearchEngine("google", "Google", "https://www.google.com/search?q=%s")
    val BING = BrowserSearchEngine("bing", "Bing", "https://www.bing.com/search?q=%s")
    val BAIDU = BrowserSearchEngine("baidu", "百度", "https://www.baidu.com/s?wd=%s")

    val ALL = listOf(DUCKDUCKGO, GOOGLE, BING, BAIDU)

    /** Historical default — matches the hardcoded DuckDuckGo behaviour of the v1 browser. */
    val DEFAULT = DUCKDUCKGO

    /** Unknown / missing ids fall back to [DEFAULT] — never crash on a stale pref. */
    fun fromId(id: String?): BrowserSearchEngine = ALL.firstOrNull { it.id == id } ?: DEFAULT
}

/**
 * Normalise raw address-bar input into a navigable URL.
 *
 *  - already absolute (`http://`, `https://`, `file://`, `about:`, `data:`) → unchanged
 *  - looks like a host (`example.com`, `foo.bar/baz`, `192.168.1.1:8080`) → prepend `https://`
 *  - everything else (no dot, no colon, has a space) → search query via the chosen engine
 */
fun normalizeBrowserQuery(raw: String, engineId: String = BrowserSearchEngines.DEFAULT.id): String {
    val q = raw.trim()
    if (q.isEmpty()) return "about:blank"
    val lower = q.lowercase()
    if (lower.startsWith("http://")
        || lower.startsWith("https://")
        || lower.startsWith("file://")
        || lower.startsWith("about:")
        || lower.startsWith("data:")
    ) return q
    // Heuristic: if it contains a dot OR a colon (port/scheme), treat as URL.
    val looksLikeHost = (q.contains('.') || q.contains(':')) && !q.contains(' ')
    return if (looksLikeHost) "https://$q" else BrowserSearchEngines.fromId(engineId).searchUrl(q)
}

/**
 * Compact host-only form of a URL for the unfocused address bar (Chrome-style: the full
 * URL is noise on a narrow screen; the user cares WHICH site they're on). Returns "" for
 * about:blank so the placeholder shows instead, and the raw string for anything that
 * doesn't parse — never lose information.
 */
fun shortHostForDisplay(url: String): String {
    if (url.isBlank() || url == "about:blank") return ""
    val host = runCatching { java.net.URI(url).host }.getOrNull()
    if (host.isNullOrBlank()) return url
    return host.removePrefix("www.")
}
