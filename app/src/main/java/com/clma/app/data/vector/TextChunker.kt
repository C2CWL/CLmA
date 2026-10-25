package com.clma.app.data.vector

/** One embeddable piece of a document, with its fingerprint. */
data class TextChunk(
    /** Position within the document, 0-based and gapless - the index's stable chunk identity. */
    val index: Int,
    val text: String,
    /** [ContentHash.of] the text that was embedded, so an unchanged chunk is never re-embedded. */
    val contentHash: Long,
)

/**
 * How a document is cut up. The defaults are tuned for the retrieval this feeds: a chunk has to
 * be small enough that its embedding is about one topic (a whole file is about everything, and
 * therefore matches nothing in particular) and large enough to keep a paragraph's meaning
 * intact.
 */
data class ChunkSpec(
    /**
     * Ceiling on a chunk's *body* in characters, not tokens: chunking must not need a tokenizer.
     * The Markdown breadcrumb prefix (see [headingBreadcrumb]) is added on top of this - it is a
     * bounded heading path, not content, and folding it into the ceiling would make the budget
     * depend on how deeply a chunk happens to be nested.
     */
    val maxChars: Int = MAX_CHARS,
    /**
     * Characters of the previous chunk repeated at the head of the next one, so a sentence that
     * straddles a boundary is still wholly present in at least one chunk.
     */
    val overlapChars: Int = OVERLAP_CHARS,
    val mode: ChunkingMode = ChunkingMode.PLAIN,
    /**
     * Prefix each chunk with the Markdown heading path it sits under (`Deploy > Release`).
     * Costs a few characters and materially improves retrieval on notes, where a chunk that
     * says "run rel7.sh" is otherwise indistinguishable from any other shell snippet.
     */
    val headingBreadcrumb: Boolean = true,
) {
    init {
        require(maxChars > 0) { "maxChars must be positive" }
        require(overlapChars >= 0 && overlapChars < maxChars) { "overlapChars must be in [0, maxChars)" }
    }

    /**
     * The same chunking, sized for a model whose window is [contextTokens] tokens.
     *
     * The budget has to come from the model, because models do not agree on a window and the
     * runtime refuses a text that does not fit rather than truncating it. The defaults above were
     * chosen when the only model was an 8K one; a 512-token model needs chunks roughly five times
     * smaller, and the alternative to giving it them is that its documents do not index.
     *
     * Never enlarges the budget: a model with a window big enough for [maxChars] chunks exactly
     * as it did before, so this is a no-op for every model that was already there.
     */
    fun fitting(contextTokens: Int): ChunkSpec {
        val budget = maxCharsFor(contextTokens)
        if (budget >= maxChars) return this
        // Kept proportional rather than fixed: 200 characters of overlap is a sixth of the
        // default budget, and a fixed overlap against a 256-character budget would be almost
        // the whole chunk.
        return copy(maxChars = budget, overlapChars = budget * overlapChars / maxChars)
    }

    companion object {
        /** The budget the app has always used: comfortable for an 8K-window model. */
        const val MAX_CHARS = 1200
        const val OVERLAP_CHARS = 200

        /**
         * Characters assumed to fit in one token, in the worst case.
         *
         * Two, not four: chunking here is deliberately tokenizer-free, so the assumption has to
         * hold for the densest script the app is used in - a CJK character is one token and a
         * rare one can be several, while an English character is a quarter of a token. Half the
         * window in characters is therefore the largest budget that is still safe everywhere,
         * and the cost of being conservative is only that small-window models get smaller chunks.
         */
        private const val MIN_CHARS_PER_TOKEN = 2

        /**
         * The character budget for a model whose window is [contextTokens] tokens, capped at
         * [MAX_CHARS]. A window we were not told about keeps the default rather than guessing.
         */
        fun maxCharsFor(contextTokens: Int): Int =
            if (contextTokens <= 0) MAX_CHARS
            else minOf(MAX_CHARS, contextTokens / MIN_CHARS_PER_TOKEN).coerceAtLeast(1)
    }
}

enum class ChunkingMode {
    /** Paragraphs, plus a forced break at every Markdown heading. */
    MARKDOWN,

    /** Top-level declarations, so a function body is never cut in half. */
    CODE,

    /** Blank-line paragraphs only. */
    PLAIN,
}

/**
 * Cuts a document into embeddable chunks.
 *
 * The strategy is "split on meaning, then pack": boundaries are found first (headings, blank
 * lines, or top-level declarations - see [ChunkingMode]), and consecutive pieces are then
 * packed up to [ChunkSpec.maxChars] rather than cut at a fixed width. A fixed-width splitter is
 * one line shorter and produces chunks that begin mid-identifier, which is exactly the kind of
 * input an embedding model is worst at.
 *
 * Pure, and therefore unit-tested on the JVM: chunk boundaries are the single easiest thing to
 * get subtly wrong and the hardest to notice afterwards - a bad boundary does not throw, it
 * just retrieves the wrong document forever.
 */
object TextChunker {

    /** Markdown ATX heading, e.g. `## Release`. */
    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")

    /**
     * Lines that start a new top-level unit in the languages this app indexes. Conservative on
     * purpose: a false positive only splits a chunk earlier than needed (harmless, the packer
     * will still fill it), while a false negative cuts a function in half.
     */
    private val CODE_BOUNDARY = Regex(
        "^(fun|class|object|interface|enum class|sealed|data class|abstract|open|internal)\\s|" +
            "^(def|async def)\\s|" +
            "^(pub )?(fn|struct|enum|impl|trait|mod)\\s|" +
            "^(export )?(async )?(function|class|const)\\s|" +
            "^(public|private|protected|static|final)\\s.*[({]\\s*$"
    )

    fun chunk(text: String, spec: ChunkSpec = ChunkSpec()): List<TextChunk> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        if (normalized.isBlank()) return emptyList()

        val pieces = when (spec.mode) {
            ChunkingMode.MARKDOWN -> markdownPieces(normalized)
            ChunkingMode.CODE -> codePieces(normalized)
            ChunkingMode.PLAIN -> paragraphPieces(normalized)
        }

        val packed = pack(pieces, spec)
        return packed.mapIndexed { index, body ->
            TextChunk(index = index, text = body, contentHash = ContentHash.of(body))
        }
    }

    // --- boundary finding --------------------------------------------------

    private data class Piece(val text: String, val headingPath: List<String>)

    private fun markdownPieces(text: String): List<Piece> {
        val pieces = mutableListOf<Piece>()
        val breadcrumb = mutableListOf<String>()
        val buffer = StringBuilder()
        // A heading with nothing under it yet. The blank line that follows it must not end the
        // piece, or every section would start with a chunk consisting of the heading alone -
        // which embeds to something generic and retrieves nothing.
        var headingOnly = false

        fun flush() {
            if (buffer.isNotBlank()) pieces += Piece(buffer.toString().trim(), breadcrumb.toList())
            buffer.setLength(0)
            headingOnly = false
        }

        for (line in text.split('\n')) {
            val heading = HEADING.matchEntire(line.trim())
            if (heading != null) {
                flush()
                val level = heading.groupValues[1].length
                while (breadcrumb.size >= level) breadcrumb.removeAt(breadcrumb.size - 1)
                breadcrumb += heading.groupValues[2].trim()
                buffer.appendLine(line)
                headingOnly = true
                continue
            }
            if (line.isBlank()) {
                if (!headingOnly) flush() else buffer.appendLine(line)
                continue
            }
            buffer.appendLine(line)
            headingOnly = false
        }
        flush()
        return pieces
    }

    private fun codePieces(text: String): List<Piece> {
        val pieces = mutableListOf<Piece>()
        val buffer = StringBuilder()

        fun flush() {
            if (buffer.isNotBlank()) pieces += Piece(buffer.toString().trim(), emptyList())
            buffer.setLength(0)
        }

        for (line in text.split('\n')) {
            if (CODE_BOUNDARY.containsMatchIn(line) && buffer.isNotBlank()) flush()
            if (line.isBlank() && buffer.length > 0) {
                // A blank line does not end a declaration (bodies contain plenty); it only ends
                // a run of comments/licence headers at the top of a file.
                buffer.appendLine(line)
                continue
            }
            buffer.appendLine(line)
        }
        flush()
        return pieces
    }

    private fun paragraphPieces(text: String): List<Piece> =
        text.split(Regex("\n\\s*\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { Piece(it, emptyList()) }

    // --- packing -----------------------------------------------------------

    /**
     * Packs [pieces] up to [ChunkSpec.maxChars] each.
     *
     * The size invariant is `body.length <= maxChars`, where the *body* excludes the breadcrumb
     * prefix: the prefix is a small, bounded addition (a heading path) rather than content, and
     * folding it into the budget would make the budget depend on where in a document the chunk
     * happens to sit.
     *
     * Overlap is taken out of the *next* chunk's budget, never added on top: a chunk that is
     * allowed to exceed the ceiling is a chunk that can silently overflow the model's context.
     * When the overlap would leave no room for the piece itself - a short piece right after a
     * long one - the overlap is dropped instead, because a chunk consisting of nothing but
     * repeated context is worse than a clean boundary.
     */
    private fun pack(pieces: List<Piece>, spec: ChunkSpec): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var headingPath: List<String> = emptyList()

        fun emit() {
            if (current.isBlank()) return
            out += decorate(current.toString().trim(), headingPath, spec)
            current.setLength(0)
        }

        for (piece in pieces) {
            if (piece.text.length > spec.maxChars) {
                // A piece larger than the budget (one enormous paragraph, or a file that is a
                // single long line). Whatever is pending belongs with it - typically the heading
                // that introduces it - so the two are joined and split together; emitting the
                // buffer separately here is what would leave a chunk consisting of a bare
                // heading.
                val pending = if (current.isBlank()) "" else current.toString().trim() + "\n"
                val path = if (current.isBlank()) piece.headingPath else headingPath
                current.setLength(0)
                hardSplit(pending + piece.text, spec).forEach { out += decorate(it, path, spec) }
                continue
            }
            // Close the pending chunk BEFORE narrowing the breadcrumb. The chunk being emitted
            // belongs to the section that was current when it started, and narrowing first (to the
            // prefix shared with the incoming piece) blanked that chunk's heading the moment the
            // next section began — every chunk that ended at a section boundary lost its title.
            if (current.isNotEmpty() && current.length + piece.text.length + 1 > spec.maxChars) {
                val previous = current.toString()
                emit()
                headingPath = piece.headingPath
                val tail = overlapTail(previous, spec.overlapChars)
                current.append(tail)
                if (tail.isNotEmpty()) current.append('\n')
                if (current.length + piece.text.length + 1 > spec.maxChars) current.setLength(0)
            }
            if (current.isEmpty()) {
                headingPath = piece.headingPath
            } else {
                // Keep the deepest heading the whole chunk sits under: a chunk that spans
                // `Deploy` and `Deploy > Release` is honestly labelled `Deploy`, while one that
                // spans `Alpha` and `Beta` shares no prefix and gets no breadcrumb at all. A
                // breadcrumb naming only the first of several sections would be a small lie told
                // to the embedding model, and a wrong breadcrumb is worse than none.
                headingPath = commonPrefix(headingPath, piece.headingPath)
            }
            current.append(piece.text)
            current.append('\n')
        }
        emit()
        return out
    }

    /** Splits an over-long body at line boundaries, honouring the ceiling. Undecorated: only
     *  the caller knows which heading path the whole thing sits under. */
    private fun hardSplit(text: String, spec: ChunkSpec): List<String> {
        val result = mutableListOf<String>()
        val budget = (spec.maxChars - spec.overlapChars).coerceAtLeast(1)
        val lines = text.split('\n')
        val current = StringBuilder()

        fun flushWithOverlap() {
            val body = current.toString().trim()
            if (body.isEmpty()) return
            result += body
            current.setLength(0)
            val tail = overlapTail(body, spec.overlapChars)
            if (tail.isNotEmpty() && tail.length + 1 < budget) {
                current.append(tail).append('\n')
            }
        }

        for (line in lines) {
            var rest = line
            // A single line longer than the whole budget (minified JSON, a base64 blob) has no
            // boundary to respect, so it is cut at exactly the budget. Anything already buffered
            // is *topped up* with the head of the line first, rather than flushed on its own -
            // otherwise a heading that introduces this very line would become a chunk of a
            // heading and nothing else.
            while (rest.length > budget) {
                if (current.isNotEmpty()) {
                    val room = budget - current.length
                    if (room > 0) {
                        current.append(rest.take(room))
                        rest = rest.drop(room)
                    }
                    flushWithOverlap()
                } else {
                    result += rest.take(budget)
                    rest = rest.drop(budget)
                }
            }
            if (current.isNotEmpty() && current.length + rest.length + 1 > budget) flushWithOverlap()
            current.append(rest).append('\n')
        }
        val body = current.toString().trim()
        if (body.isNotEmpty()) result += body
        return result
    }

    /** The last [chars] characters of [text], backed up to a line start so nothing is cut mid-word. */
    private fun overlapTail(text: String, chars: Int): String {
        if (chars <= 0) return ""
        val body = text.trimEnd('\n')
        if (body.length <= chars) return ""
        val start = body.length - chars
        val newline = body.indexOf('\n', start)
        return if (newline in 0 until body.length - 1) body.substring(newline + 1) else body.substring(start)
    }

    /** The deepest heading both chunks sit under; empty when they share none. */
    private fun commonPrefix(a: List<String>, b: List<String>): List<String> {
        val limit = minOf(a.size, b.size)
        var i = 0
        while (i < limit && a[i] == b[i]) i++
        return a.subList(0, i)
    }

    private fun decorate(body: String, headingPath: List<String>, spec: ChunkSpec): String {
        if (!spec.headingBreadcrumb || headingPath.isEmpty()) return body
        return headingPath.joinToString(" > ") + "\n" + body
    }
}
