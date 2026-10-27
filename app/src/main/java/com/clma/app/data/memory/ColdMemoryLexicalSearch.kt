package com.clma.app.data.memory

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.ln

/** One passage `memory_search` found, with the file it came from. */
data class ColdMemorySearchHit(
    val file: String,
    val chunkIndex: Int,
    val score: Float,
    val text: String,
)

/**
 * What `memory_search` returns for one call.
 *
 * [available] is false when the directory cannot be read at all (no workspace bound to the
 * assistant, a deleted workspace, an unreadable path). That is a state the tool reports rather
 * than throws: the model still has to be told what to do instead.
 */
data class ColdMemorySearchOutcome(
    val available: Boolean,
    val hits: List<ColdMemorySearchHit> = emptyList(),
    val indexedDocuments: Int = 0,
    val note: String? = null,
)

/**
 * The JSON `memory_search` answers with.
 *
 * Split out of the tool itself so it can be tested without the tool stack: this is where the
 * wording that steers the model lives — what to do when nothing is indexed, when nothing matched,
 * when the directory cannot be read at all — and a wording bug here is invisible until a model
 * does the wrong thing with it.
 */
object MemorySearchEnvelope {

    fun hits(dirLabel: String, query: String, outcome: ColdMemorySearchOutcome): String =
        buildJsonObject {
            put("dir", dirLabel)
            put("query", query)
            put("indexedDocuments", outcome.indexedDocuments)
            put("hits", buildJsonArray {
                outcome.hits.forEach { hit ->
                    add(buildJsonObject {
                        put("file", hit.file)
                        put("chunkIndex", hit.chunkIndex)
                        put("score", hit.score)
                        put("text", hit.text.take(MAX_HIT_CHARS))
                        if (hit.text.length > MAX_HIT_CHARS) put("truncated", true)
                    })
                }
            })
            if (outcome.hits.isEmpty()) {
                put("note", emptyNote(outcome.indexedDocuments))
                if (outcome.note != null) put("detail", outcome.note!!)
            }
        }.toString()

    /**
     * Sent when the knowledge base cannot be searched at all.
     *
     * The hint is an instruction, not an alternative: there is no index to rebuild and no model to
     * install, so the only useful thing to say is which of the exact-name tools still answers.
     */
    fun unavailable(detail: String?): String = buildJsonObject {
        put("error", "unavailable")
        put("detail", detail ?: "cold memory cannot be read right now")
        put(
            "hint",
            "The knowledge base could not be read. Use memory_index to see what exists, or " +
                "memory_read when you already know the file name.",
        )
    }.toString()

    fun missingQuery(): String = buildJsonObject {
        put("error", "missing_argument")
        put("detail", "query is required")
    }.toString()

    private fun emptyNote(indexedDocuments: Int): String = if (indexedDocuments == 0) {
        "No documents were found in this directory. Use memory_index to confirm the path, or " +
            "memory_write to create a note."
    } else {
        "No passage matched those words. Try different or fewer keywords, or memory_index to " +
            "see what exists."
    }
}

/** Passages one search returns unless the model asks for a different number. */
const val DEFAULT_SEARCH_LIMIT = 8

/** Upper bound on `limit`: a caller asking for a hundred passages is asking for a context blowup. */
const val MAX_SEARCH_LIMIT = 20

/** Characters of a passage returned to the model; the full text stays a `memory_read` away. */
const val MAX_HIT_CHARS = 800

/**
 * Keyword search over the cold-memory knowledge base, done on the spot.
 *
 * There is no index to build, persist or migrate: `memory_search` lists the directory, reads the
 * Markdown files, splits each one into overlapping passages and scores them with BM25 — all inside
 * the one call. A knowledge base of a few hundred kilobytes costs a few milliseconds, and in
 * exchange a note becomes searchable the instant it is written, with nothing to get stale and no
 * model to install.
 *
 * Tokenisation is deliberately plain and script-aware: ASCII words are lowercased, and CJK runs
 * are cut into unigrams *and* bigrams, because Chinese has no spaces and a bigram is the smallest
 * unit that carries meaning ("向量" must not degrade into a hit on either character alone). BM25
 * then weighs rare terms above common ones, so a passage about `BM25` outranks one that happens to
 * contain "the" five times.
 *
 * What this deliberately is not: semantic. Asking for "how do I get my screen read" will not find
 * a note titled "read_screen_text" unless the words overlap. The tool description says so, and
 * `memory_index` remains the browse-by-name path for the cases where words do not overlap.
 */
object ColdMemoryLexicalSearch {

    /** Passage length used when splitting a document. */
    const val CHUNK_CHARS = 900

    /**
     * Characters consecutive passages share. Retrieval noise, not elegance: a sentence split
     * across a boundary is otherwise in neither passage in full.
     */
    const val CHUNK_OVERLAP = 120

    /** How many hits one file may contribute, so a long document cannot fill the whole result. */
    const val MAX_HITS_PER_FILE = 2

    private const val K1 = 1.2f
    private const val B = 0.75f

    /** Weight applied to a file name's tokens, on top of the passage body. */
    private const val FILE_NAME_WEIGHT = 2

    /** One Markdown document, already read from disk. */
    data class Document(val name: String, val text: String)

    /**
     * Scores [documents] against [query] and returns the best passages.
     *
     * Pure and synchronous by design: the caller owns the IO, so this is unit-testable with plain
     * strings and the tool contract (`buildMemorySearchTool`) stays a thin wrapper.
     */
    fun search(
        documents: List<Document>,
        query: String,
        limit: Int = DEFAULT_SEARCH_LIMIT,
    ): ColdMemorySearchOutcome {
        val marks = documents.filter { it.text.isNotBlank() }
        if (marks.isEmpty()) {
            return ColdMemorySearchOutcome(available = true, indexedDocuments = documents.size)
        }

        val queryTerms = tokenize(query).distinct()
        if (queryTerms.isEmpty()) {
            return ColdMemorySearchOutcome(available = true, indexedDocuments = marks.size)
        }

        val passages = ArrayList<Passage>()
        for (doc in marks) {
            split(doc.text).forEachIndexed { index, body ->
                val bodyTokens = tokenize(body)
                passages += Passage(
                    file = doc.name,
                    chunkIndex = index,
                    text = body,
                    // BM25 length normalisation measures the passage body only: the weighted file
                    // name below is a scoring boost, not part of how long the passage is.
                    length = maxOf(1, bodyTokens.size),
                    terms = termFrequencies(bodyTokens, doc.name),
                )
            }
        }
        if (passages.isEmpty()) {
            return ColdMemorySearchOutcome(available = true, indexedDocuments = marks.size)
        }

        val total = passages.size
        val averageLength = passages.sumOf { it.length }.toDouble() / total

        val documentFrequency = HashMap<String, Int>(queryTerms.size * 4)
        for (term in queryTerms) {
            var df = 0
            for (passage in passages) if (passage.terms.containsKey(term)) df++
            documentFrequency[term] = df
        }

        val scored = ArrayList<ColdMemorySearchHit>()
        for (passage in passages) {
            var score = 0.0f
            for (term in queryTerms) {
                val tf = passage.terms[term] ?: continue
                val df = documentFrequency[term] ?: 0
                if (df == 0) continue
                val idf = ln(1.0 + (total - df + 0.5) / (df + 0.5)).toFloat()
                val denominator = tf + K1 * (1f - B + B * (passage.length / averageLength).toFloat())
                score += idf * (tf * (K1 + 1f)) / denominator
            }
            if (score > 0f) {
                scored += ColdMemorySearchHit(
                    file = passage.file,
                    chunkIndex = passage.chunkIndex,
                    score = score,
                    text = passage.text.trim(),
                )
            }
        }

        val perFile = HashMap<String, Int>()
        val hits = scored
            .sortedWith(compareByDescending<ColdMemorySearchHit> { it.score }.thenBy { it.file }
                .thenBy { it.chunkIndex })
            .filter { hit ->
                val seen = perFile[hit.file] ?: 0
                if (seen >= MAX_HITS_PER_FILE) {
                    false
                } else {
                    perFile[hit.file] = seen + 1
                    true
                }
            }
            .take(limit.coerceIn(1, MAX_SEARCH_LIMIT))

        return ColdMemorySearchOutcome(
            available = true,
            hits = hits,
            indexedDocuments = marks.size,
        )
    }

    /**
     * Splits [text] into passages of at most [CHUNK_CHARS], overlapping by [CHUNK_OVERLAP].
     *
     * Prefers to cut at a line break inside the last quarter of the window, so a passage usually
     * ends on a paragraph or list item instead of mid-sentence.
     */
    fun split(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        if (text.length <= CHUNK_CHARS) return listOf(text)

        val chunks = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            val hardEnd = minOf(text.length, start + CHUNK_CHARS)
            if (hardEnd == text.length) {
                chunks += text.substring(start)
                break
            }
            val windowFloor = start + (CHUNK_CHARS * 3) / 4
            val newline = text.lastIndexOf('\n', hardEnd - 1)
            val end = if (newline > windowFloor) newline + 1 else hardEnd
            chunks += text.substring(start, end)
            val next = maxOf(end - CHUNK_OVERLAP, start + 1)
            start = next
        }
        return chunks
    }

    /** Lowercased ASCII words, plus CJK unigrams and bigrams. */
    fun tokenize(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val lower = text.lowercase()
        val out = ArrayList<String>(lower.length / 2 + 8)
        val cjk = StringBuilder()

        fun flushCjk() {
            if (cjk.isEmpty()) return
            val run = cjk.toString()
            for (ch in run) out += ch.toString()
            var i = 0
            while (i + 1 < run.length) {
                out += run.substring(i, i + 2)
                i++
            }
            cjk.setLength(0)
        }

        var i = 0
        while (i < lower.length) {
            val ch = lower[i]
            if (isCjk(ch)) {
                cjk.append(ch)
                i++
            } else if (isAsciiWordChar(ch)) {
                flushCjk()
                val begin = i
                while (i < lower.length && isAsciiWordChar(lower[i])) i++
                out += lower.substring(begin, i)
            } else {
                flushCjk()
                i++
            }
        }
        flushCjk()
        return out
    }

    private fun termFrequencies(bodyTokens: List<String>, fileName: String): HashMap<String, Float> {
        val terms = HashMap<String, Float>()
        for (token in bodyTokens) {
            terms[token] = (terms[token] ?: 0f) + 1f
        }
        // The file name is the strongest statement a note makes about its own topic, so it is
        // indexed with every passage even when the body never repeats it.
        val nameTokens = tokenize(fileName.removeSuffix(".md").replace('-', ' ').replace('_', ' '))
        for (token in nameTokens) {
            terms[token] = (terms[token] ?: 0f) + FILE_NAME_WEIGHT
        }
        return terms
    }

    private fun isCjk(ch: Char): Boolean = when (ch.code) {
        in 0x3040..0x30FF, // kana
        in 0x3400..0x4DBF, // CJK extension A
        in 0x4E00..0x9FFF, // CJK unified ideographs
        in 0xF900..0xFAFF, // compatibility ideographs
        in 0xFF66..0xFF9D, // half-width katakana
        in 0xAC00..0xD7AF, // hangul syllables
        -> true
        else -> false
    }

    private fun isAsciiWordChar(ch: Char): Boolean =
        ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '_'

    private class Passage(
        val file: String,
        val chunkIndex: Int,
        val text: String,
        val length: Int,
        val terms: HashMap<String, Float>,
    )
}