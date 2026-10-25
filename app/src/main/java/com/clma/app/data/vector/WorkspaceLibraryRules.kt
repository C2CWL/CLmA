package com.clma.app.data.vector

/**
 * What the file library is allowed to contain, and how much of it one round may read.
 *
 * Moxw - the library is a directory in the workspace that the user points at, and the index reads
 * it recursively. Both halves of that sentence are why this object exists: a workspace can hold
 * anything (the one this was built against holds two repository clones, a gradle cache and a pile
 * of jars), so the walk needs an allow-list rather than a deny-list, and reading it can cost
 * minutes of embedding, so one round needs a budget.
 *
 * The allow-list is by extension. That is a deliberate simplification: it means every file the
 * index touches is one whose text we can actually read, and it makes "why is this file missing"
 * answerable by looking at one set. A deny-list would have to enumerate binaries, and would get
 * the next one wrong.
 *
 * Images are the one kind whose text is not *in* the file: it is read out of them by on-device text
 * recognition, so a screenshot of a stack trace is searchable by the words in it. That is also why
 * they carry numbers of their own - see [ESTIMATED_CHARS_PER_IMAGE] and [MAX_IMAGES_PER_ROUND].
 */
enum class LibraryFileKind { TEXT, CODE, DOCUMENT, IMAGE, AUDIO, VIDEO }

/**
 * True for the two kinds whose rows are their own segments rather than their text.
 *
 * Top-level rather than a member of [WorkspaceLibraryRules] so that every reader - the round
 * planner, the coordinator, the search envelope - can ask the same question without importing an
 * object's scope. A media hit is a time range, not a passage, and that distinction is needed in
 * more than one place.
 */
val LibraryFileKind.isMedia: Boolean
    get() = this == LibraryFileKind.AUDIO || this == LibraryFileKind.VIDEO

/** Which `:document` parser reads a file, if any. Kept here so the mapping is testable. */
enum class LibraryDocumentParser { PDF, DOCX, PPTX, EPUB }

object WorkspaceLibraryRules {

    /** Where the library lives until the user picks somewhere else. Relative to the workspace. */
    const val DEFAULT_DIR = "library"

    /**
     * Per-file caps. Text is capped by bytes because we read all of it; a document is capped by
     * bytes as a proxy for cost, and its *extracted* text is capped separately - a 30 MB PDF of
     * scanned images extracts to very little, while a text-heavy one extracts to more than any
     * model call wants to see.
     */
    const val MAX_TEXT_FILE_BYTES = 2_000_000L
    const val MAX_DOCUMENT_FILE_BYTES = 30_000_000L
    const val MAX_TEXT_CHARS = 400_000
    const val MAX_DOCUMENT_CHARS = 400_000

    /**
     * An image is capped by bytes because decoding one costs memory rather than time, and by the
     * characters its recognised text may keep - a screenshot of a full log legitimately runs to
     * thousands of characters, while a photograph of a wall produces none at all.
     */
    const val MAX_IMAGE_FILE_BYTES = 15_000_000L
    const val MAX_IMAGE_CHARS = 40_000

    /**
     * A recording or a video is capped by bytes only as a guard against a file the phone cannot
     * even open; the cost that matters is duration, and it is charged separately.
     */
    const val MAX_MEDIA_FILE_BYTES = 2_000_000_000L

    /**
     * The longest piece of media the index will take on, in seconds.
     *
     * Thirty minutes is twenty-four audio segments or twelve video frames. Beyond that the file is
     * left out rather than indexed partially: the index replaces a document's rows as a whole, so a
     * half-indexed recording would be re-embedded from the start every round, forever.
     */
    const val MAX_MEDIA_SECONDS = 1_800

    /**
     * How many seconds of media one round may embed.
     *
     * A round is a background job on a phone, and an audio segment costs an encoder pass: this is
     * the number that keeps a folder of podcasts from turning the first pass into a stove. It is
     * deliberately as large as [MAX_MEDIA_SECONDS] so that a single accepted file always fits -
     * a file that can never fit its round would be deferred forever.
     */
    const val MAX_MEDIA_SECONDS_PER_ROUND = 1_800

    /**
     * How many media files one round may open.
     *
     * Separate from the seconds above because opening one costs a container parse and, for a video,
     * a `MediaMetadataRetriever`; a folder of two hundred voice memos would otherwise spend the
     * round's whole time in metadata reads.
     */
    const val MAX_MEDIA_FILES_PER_ROUND = 4

    /** Frames one round may hand to the vision encoder, across every video in it. */
    const val MAX_VIDEO_FRAMES_PER_ROUND = 24

    /**
     * One round's budget. This is the number that keeps a phone from embedding for an hour: at
     * roughly 4k characters per chunk, [MAX_CHARS_PER_ROUND] is a few hundred model calls, which
     * is minutes rather than tens of minutes, and [MAX_FILES_PER_ROUND] bounds the directory
     * traffic for a library of many tiny files.
     */
    const val MAX_CHARS_PER_ROUND = 1_500_000
    const val MAX_FILES_PER_ROUND = 400

    /**
     * What an image is charged against the round budget, whatever it weighs on disk.
     *
     * This is not a prediction of how much text will come out - that is bounded by [MAX_IMAGE_CHARS].
     * It is a floor on the estimate, and it exists because the alternative is wrong in a way that
     * never finishes: a library can hold a folder of 4 MB photographs, and charging those at their
     * byte count - the way text is charged - spends most of a round's budget on the first one. The
     * real cost of an image is a decode plus a recognition pass, and neither grows with the file
     * size, so neither should what it is charged.
     */
    const val ESTIMATED_CHARS_PER_IMAGE = 1_200

    /**
     * How many images one round may hand to the *vision* encoder.
     *
     * Two orders of magnitude below [MAX_IMAGES_PER_ROUND], and for a different reason: recognising
     * text in a picture is on-device and cheap, while embedding the picture itself runs the vision
     * tower - measured at ~2.1s per image against ~60ms for a text on a Snapdragon 8 Elite. A round
     * is a background job on a phone, so this cap is what keeps the first pass over a folder of
     * photographs from turning into a stove. A deferred image keeps its place like any other file,
     * so a large library still converges - just over more rounds.
     */
    const val MAX_NATIVE_IMAGES_PER_ROUND = 4

    /**
     * How many images one round may recognise.
     *
     * Recognition costs on the order of a tenth of a second per image, where a text file costs
     * milliseconds, so [MAX_FILES_PER_ROUND] alone would let a round of 400 photographs run for a
     * minute in the background. Images are also the kind most likely to be numerous and least
     * likely to have changed, which makes bounding them per round and letting the next round carry
     * on the cheap way to stay responsive. A deferred image keeps its place like any other file, so
     * a library of a thousand screenshots converges, only more slowly.
     */
    const val MAX_IMAGES_PER_ROUND = 40

    private val DECLARED_DOCUMENT = setOf("pdf", "docx", "pptx", "epub")

    /**
     * Formats the recognizer can read and `BitmapFactory` can decode on the oldest supported API.
     * Deliberately no video and no audio: those need a model of their own, and what is worth
     * searching in them is the speech, which has its own pipeline already.
     */
    private val DECLARED_IMAGE = setOf("png", "jpg", "jpeg", "webp", "bmp", "gif", "heic", "heif")

    /**
     * Containers the platform decoders read. Audio and video are separate sets rather than one
     * "media" set because what the index does with them differs in kind: a recording is embedded by
     * its sound, a video by a dozen frames.
     */
    private val DECLARED_AUDIO = setOf("m4a", "mp3", "wav", "ogg", "oga", "opus", "flac", "aac", "amr", "3ga", "wma")
    private val DECLARED_VIDEO = setOf("mp4", "m4v", "mkv", "webm", "mov", "3gp")

    private val DECLARED_TEXT = setOf(
        "md", "markdown", "txt", "text", "rst", "org", "adoc",
        "csv", "tsv", "log", "ndjson", "jsonl",
        "json", "json5", "yaml", "yml", "toml", "ini", "conf", "cfg", "properties", "env",
        "xml", "svg", "html", "htm", "srt", "vtt", "tex", "bib", "po", "patch", "diff",
    )

    private val DECLARED_CODE = setOf(
        "kt", "kts", "java", "gradle", "groovy", "scala", "clj", "cljs", "ex", "exs", "erl", "hs", "ml",
        "py", "pyi", "rb", "php", "pl", "lua", "r", "jl", "dart", "swift", "m", "mm", "go", "rs",
        "c", "h", "cc", "cpp", "cxx", "hpp", "hh", "cs", "fs", "vb", "asm", "s",
        "js", "jsx", "ts", "tsx", "mjs", "cjs", "vue", "svelte", "astro",
        "sh", "bash", "zsh", "fish", "ksh", "bat", "cmd", "ps1",
        "sql", "graphql", "gql", "proto", "tf", "hcl", "cmake", "mk", "nix", "vim",
    )

    /** Extension-less files that are still worth indexing, by their conventional names. */
    private val BARE_NAMES_TEXT = setOf(
        "readme", "license", "licence", "notice", "changelog", "changes", "authors", "contributors",
        "todo", "copying", "install",
    )
    private val BARE_NAMES_CODE = setOf(
        "makefile", "dockerfile", "containerfile", "rakefile", "gemfile", "procfile", "justfile",
    )

    /**
     * Directories never walked into. Every dot-directory is excluded at once (`.git`, `.gradle`,
     * `.venv`, `.idea`, `.cxx`), which is the bulk of what makes a workspace huge, plus the
     * regenerated trees that are cheap to rebuild and expensive to embed.
     */
    private val IGNORED_DIRECTORY_NAMES = setOf(
        "node_modules", "build", "dist", "out", "target", "vendor", "venv", "env",
        "__pycache__", "coverage", "Pods", "DerivedData", "tmp", "logs",
    )

    fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun isMarkdown(name: String): Boolean = extensionOf(name) in setOf("md", "markdown")

    /** `null` means the index never looks at this file. */
    fun kindOf(name: String): LibraryFileKind? {
        val extension = extensionOf(name)
        if (extension.isNotEmpty()) {
            return when (extension) {
                in DECLARED_DOCUMENT -> LibraryFileKind.DOCUMENT
                in DECLARED_IMAGE -> LibraryFileKind.IMAGE
                in DECLARED_AUDIO -> LibraryFileKind.AUDIO
                in DECLARED_VIDEO -> LibraryFileKind.VIDEO
                in DECLARED_CODE -> LibraryFileKind.CODE
                in DECLARED_TEXT -> LibraryFileKind.TEXT
                else -> null
            }
        }
        val bare = name.lowercase()
        return when {
            bare in BARE_NAMES_CODE -> LibraryFileKind.CODE
            bare in BARE_NAMES_TEXT -> LibraryFileKind.TEXT
            else -> null
        }
    }

    /** The parser that reads a document, or null when this file is not one. */
    fun documentParserFor(name: String): LibraryDocumentParser? =
        if (kindOf(name) != LibraryFileKind.DOCUMENT) {
            null
        } else {
            when (extensionOf(name)) {
                "pdf" -> LibraryDocumentParser.PDF
                "docx" -> LibraryDocumentParser.DOCX
                "pptx" -> LibraryDocumentParser.PPTX
                "epub" -> LibraryDocumentParser.EPUB
                else -> null
            }
        }

    /**
     * How a file is cut up. Code has its own mode because a function body split in half embeds as
     * two unrelated things; prose is cut at headings when it has them, at paragraphs when it
     * does not; a document is prose whatever it was authored in.
     */
    fun chunkModeOf(kind: LibraryFileKind, name: String): ChunkingMode = when (kind) {
        LibraryFileKind.CODE -> ChunkingMode.CODE
        LibraryFileKind.TEXT -> if (isMarkdown(name)) ChunkingMode.MARKDOWN else ChunkingMode.PLAIN
        LibraryFileKind.DOCUMENT -> ChunkingMode.PLAIN
        // Recognised text arrives as lines: no markup, and no structure worth trusting.
        LibraryFileKind.IMAGE -> ChunkingMode.PLAIN
        // Media never reaches the chunker - its rows are its segments - but the mapping is total.
        LibraryFileKind.AUDIO, LibraryFileKind.VIDEO -> ChunkingMode.PLAIN
    }

    fun isIgnoredDirectory(name: String): Boolean =
        name.startsWith(".") || name in IGNORED_DIRECTORY_NAMES

    /**
     * True when any segment of a relative path sits in an ignored directory. Checked per segment
     * rather than as a prefix so that `a/node_modules/b.md` is skipped as reliably as
     * `node_modules/b.md`.
     */
    fun isIgnoredPath(relativePath: String): Boolean =
        relativePath.split('/').any { it.isNotEmpty() && isIgnoredDirectory(it) }

    fun sizeCapOf(kind: LibraryFileKind): Long = when (kind) {
        LibraryFileKind.DOCUMENT -> MAX_DOCUMENT_FILE_BYTES
        LibraryFileKind.IMAGE -> MAX_IMAGE_FILE_BYTES
        LibraryFileKind.AUDIO, LibraryFileKind.VIDEO -> MAX_MEDIA_FILE_BYTES
        else -> MAX_TEXT_FILE_BYTES
    }

    fun charCapOf(kind: LibraryFileKind): Int = when (kind) {
        LibraryFileKind.DOCUMENT -> MAX_DOCUMENT_CHARS
        LibraryFileKind.IMAGE -> MAX_IMAGE_CHARS
        // Unused: a media file's text is the labels this app writes, never its own bytes.
        LibraryFileKind.AUDIO, LibraryFileKind.VIDEO -> MAX_MEDIA_CHARS
        else -> MAX_TEXT_CHARS
    }

    /**
     * Roughly how much text a file will produce, used to decide whether there is room for it in
     * this round without reading it first.
     *
     * For text the byte count *is* the estimate (UTF-8, and an over-estimate for non-Latin scripts
     * only makes the budget more conservative). For a document the ratio is a guess - four bytes
     * of PDF per character of text - and it is a guess on purpose: the alternative is parsing
     * every file to find out it does not fit. Being wrong costs one round's budget, not
     * correctness.
     */
    fun estimatedChars(kind: LibraryFileKind, sizeBytes: Long): Int = when (kind) {
        LibraryFileKind.DOCUMENT ->
            (sizeBytes / 4).coerceAtMost(MAX_DOCUMENT_CHARS.toLong()).toInt()
        // Independent of the file size on purpose: see [ESTIMATED_CHARS_PER_IMAGE].
        LibraryFileKind.IMAGE -> ESTIMATED_CHARS_PER_IMAGE
        // Charged nothing against the character budget: a media file's cost is its duration, and
        // it is bounded by [MAX_MEDIA_SECONDS_PER_ROUND] as the round reads it.
        LibraryFileKind.AUDIO, LibraryFileKind.VIDEO -> 0
        else ->
            sizeBytes.coerceAtMost(MAX_TEXT_CHARS.toLong()).toInt()
    }

    /**
     * Which indexed documents a round should forget.
     *
     * [seen] is everything the walk mentioned - read this round or not. Only a document that is
     * absent from it is taken as deleted, which is the only evidence of deletion available. This
     * is the rule that stops a round with a budget from deleting the previous round's work: the
     * first version of the sync treated "not read this round" as "gone", and would have re-embeds
     * the same prefix forever while never keeping the rest. It lives here, tested, rather than
     * inline in the sync where it was easy to get wrong twice.
     */
    fun toForget(indexed: Collection<String>, seen: Collection<String>): List<String> =
        RoundBudget.toForget(indexed, seen)

    /** One file the walk found and the allow-list accepted. */
    data class Candidate(
        /** Workspace-absolute path, the form every other tool takes. */
        val path: String,
        val name: String,
        val kind: LibraryFileKind,
        val sizeBytes: Long,
    )

    data class Round(
        val take: List<Candidate>,
        /** Accepted files that did not fit this round's budget. */
        val deferred: Int,
        /** Files refused by a size cap. Reported so a missing file can be explained. */
        val tooBig: List<String>,
    )

    /**
     * Chooses what to read this round.
     *
     * Files the index has never seen come first, then everything else in path order. That
     * ordering is what lets a library bigger than one round's budget converge: the second round
     * picks up where the first stopped instead of re-reading the same prefix forever, and a
     * library that fits in one round is read in a stable order.
     */
    fun planRound(
        candidates: List<Candidate>,
        indexed: Set<String>,
        budgetChars: Int = MAX_CHARS_PER_ROUND,
        maxFiles: Int = MAX_FILES_PER_ROUND,
        maxImages: Int = MAX_IMAGES_PER_ROUND,
        maxMedia: Int = MAX_MEDIA_FILES_PER_ROUND,
    ): Round {
        val planned = RoundBudget.plan(
            items = candidates,
            key = { it.path },
            sizeOf = { it.sizeBytes },
            capOf = { sizeCapOf(it.kind) },
            estimateChars = { estimatedChars(it.kind, it.sizeBytes) },
            indexed = indexed,
            budgetChars = budgetChars,
            maxItems = maxFiles,
        )
        // The image cap is applied after the shared planner rather than inside it: the planner is
        // generic over what a document is, and this rule is about one kind of file. Dropping from
        // the tail keeps the order the planner chose - never read first, then by path - so the
        // images a round postpones are the ones it would have reached last, and the next round
        // carries on where this one stopped instead of starting over.
        val withImages = capImages(planned.take, maxImages)
        val take = capMedia(withImages, maxMedia)
        return Round(
            take = take,
            deferred = planned.deferred + (planned.take.size - take.size),
            tooBig = planned.tooBig,
        )
    }

    /**
     * Keeps the first [maxImages] images of a plan, whatever else is in it.
     *
     * Files that are not images are never dropped by this cap: a round that filled up on
     * photographs must still index the notes beside them.
     */
    private fun capImages(take: List<Candidate>, maxImages: Int): List<Candidate> {
        val limit = maxImages.coerceAtLeast(0)
        if (take.count { it.kind == LibraryFileKind.IMAGE } <= limit) return take
        var kept = 0
        return take.filter { candidate ->
            if (candidate.kind != LibraryFileKind.IMAGE) return@filter true
            kept++
            kept <= limit
        }
    }

    /**
     * Keeps the first [maxMedia] recordings and videos of a plan, whatever else is in it.
     *
     * The same shape as [capImages] and for the same reason: opening one is an order of magnitude
     * more expensive than reading a note, and a library with a folder of videos must still index
     * the notes beside them.
     */
    private fun capMedia(take: List<Candidate>, maxMedia: Int): List<Candidate> {
        val limit = maxMedia.coerceAtLeast(0)
        if (take.count { it.kind.isMedia } <= limit) return take
        var kept = 0
        return take.filter { candidate ->
            if (!candidate.kind.isMedia) return@filter true
            kept++
            kept <= limit
        }
    }

    /**
     * What an image with no recognisable text is indexed as.
     *
     * A file whose text comes out empty is skipped, and a skipped file is one the next round tries
     * again - for a photograph of a wall, forever. Indexing its name instead costs one row and
     * makes the file findable by name, which is the only handle it has.
     */
    fun placeholderForImage(name: String): String = "$IMAGE_PLACEHOLDER_PREFIX$name"

    /** Marks a row that stands for an image itself rather than for text read out of one. */
    const val IMAGE_PLACEHOLDER_PREFIX = "Image: "

    /** Upper bound on the label text of one media row; unused by the chunker, used by the reader. */
    const val MAX_MEDIA_CHARS = 200
}
