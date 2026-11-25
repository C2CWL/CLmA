package com.clma.document

import com.artifex.mupdf.fitz.PDFDocument
import java.io.File

// Each page allocates a native fitz Page and StructuredText that only finalize() releases;
// without an explicit destroy() call these pile up as native memory for as long as this pass
// runs. MAX_PAGES also bounds a pathological or malicious PDF: a page-by-page prompt dump has
// no use past a few hundred pages anyway, since no LLM context window holds that much text.
private const val MAX_PAGES = 300

object PdfParser {
    fun parserPdf(file: File): String = parserPdf(file, Int.MAX_VALUE)

    private fun appendBounded(out: StringBuilder, limit: Int, text: String) {
        val room = limit - out.length
        if (room <= 0 || text.isEmpty()) return
        if (text.length <= room) out.append(text) else out.append(text, 0, room)
    }

    /**
     * @param maxChars stop once the accumulated text reaches this many characters. Later pages
     * are not loaded. The result may be a prefix and is not cached as a complete extraction.
     */
    fun parserPdf(file: File, maxChars: Int): String {
        val document = PDFDocument.openDocument(file.absolutePath).asPDF()
        try {
            val pageCount = document.countPages()
            val pagesToRead = minOf(pageCount, MAX_PAGES)
            val result = StringBuilder()
            val limit = maxChars.coerceAtLeast(0)
            var stoppedEarly = false
            for (i in 0 until pagesToRead) {
                if (result.length >= limit) {
                    stoppedEarly = true
                    break
                }
                val page = document.loadPage(i)
                try {
                    val structuredText = page.toStructuredText()
                    try {
                        appendBounded(result, limit, "---Page ${i + 1}:\n")
                        appendBounded(result, limit, structuredText.asText())
                        appendBounded(result, limit, "\n")
                    } finally {
                        structuredText.destroy()
                    }
                } finally {
                    page.destroy()
                }
            }
            if (!stoppedEarly && pagesToRead < pageCount && result.length < limit) {
                appendBounded(result, limit, "\n[Truncated: showing $pagesToRead of $pageCount pages]")
            }
            return result.toString()
        } finally {
            document.destroy()
        }
    }
}
