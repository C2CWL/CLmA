package com.clma.app.data.ai.document

import java.util.concurrent.ConcurrentHashMap

/**
 * Hand-off between DocumentAsPromptTransformer (which knows a document was cut) and ChatService
 * (which can show the user a warning). Keyed by document URL; each document is announced once
 * per app process.
 */
object DocumentInlineNotices {
    data class Notice(val url: String, val fileName: String, val shownPercent: Int)

    private val pending = ConcurrentHashMap<String, Notice>()
    private val announced = ConcurrentHashMap.newKeySet<String>()

    fun report(notice: Notice) {
        if (notice.url !in announced) pending[notice.url] = notice
    }

    fun hasPending(): Boolean = pending.isNotEmpty()

    /** Notices for the given document URLs that have not been shown yet; marks them shown. */
    fun takeFor(urls: Collection<String>): List<Notice> = urls.distinct().mapNotNull { url ->
        val notice = pending.remove(url) ?: return@mapNotNull null
        if (announced.add(url)) notice else null
    }

    internal fun clear() {
        pending.clear()
        announced.clear()
    }
}
