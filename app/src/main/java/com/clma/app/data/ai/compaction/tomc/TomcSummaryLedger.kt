package com.clma.app.data.ai.compaction.tomc

/**
 * A deterministic, conservative state block carried by LLM compaction summaries.
 *
 * This is deliberately not a general fact extractor: only explicit task declarations and
 * explicit lifecycle statements are retained. A mentioned path/version is not a fact.
 */
internal object TomcSummaryLedger {
    const val BEGIN = "[CLMA_TOMC_LEDGER v1]"
    const val END = "[/CLMA_TOMC_LEDGER]"
    private const val DEFAULT_LIMIT = 24
    private val block = Regex("(?s)\\[CLMA_TOMC_LEDGER v1].*?\\[/CLMA_TOMC_LEDGER]")

    enum class Status(val wire: String, val label: String) {
        PLANNED("planned", "计划"),
        EXECUTED("executed", "已执行"),
        VERIFIED("verified", "已验证"),
        ROLLED_BACK("rolled_back", "已回滚"),
        FAILED("failed", "失败"),
    }

    data class Entry(
        val status: Status,
        val detail: String,
        val sourceTurn: Int,
        val task: Boolean = false,
    )

    data class Extraction(
        val entries: List<Entry>,
        val inputChars: Int,
        val emitted: Boolean,
    )

    private val task = Regex(
        "(?:^|[。；;]\\s*)(?:当前)?(?:任务|目标|需求)\\s*(?:是|为|：|:)\\s*(.{4,240})",
        RegexOption.IGNORE_CASE,
    )
    private val lifecycle = listOf(
        Status.ROLLED_BACK to Regex(
            "(?:^|[。；;]\\s*)((?:已|已经|确认)?(?:回滚|撤销|还原|回退)(?:了|完成|成功)?\\s*.{2,240})",
        ),
        Status.FAILED to Regex(
            "(?:^|[。；;]\\s*)((?:[^。；;\\n]{0,100}(?:失败|未通过|报错|错误)|(?:失败|报错)[:：]).{0,200})",
        ),
        Status.VERIFIED to Regex(
            "(?:^|[。；;]\\s*)((?:已|已经)?(?:验证|测试|检查|单测|CI|构建)(?:通过|成功|全绿|完毕)?|" +
                "(?:已|已经)(?:验证|测试|检查)|" +
                "(?:测试|验证|CI|构建)(?:已)?(?:通过|成功|全绿)|\\d+\\s*(?:项|个)?测试(?:全部)?通过)" +
                "\\s*[^。；;\\n]{0,220}",
            RegexOption.IGNORE_CASE,
        ),
        Status.EXECUTED to Regex(
            "(?:^|[。；;]\\s*)((?:已|已经)(?:完成|执行|创建|修改|更新|添加|修复|提交|推送|安装|部署|启动|停止|删除)|" +
                "(?:完成|执行|创建|修改|更新|添加|修复|提交|推送|安装|部署|启动|停止|删除)(?:成功|完毕))" +
                "\\s*[：:，,]?\\s*[^。；;\\n]{2,220}",
        ),
        Status.PLANNED to Regex(
            "(?:^|[。；;]\\s*)((?:计划|准备|打算|接下来(?:要|会)?|TODO)\\s*(?:是|为|：|:|，,)?\\s*.{2,240})",
            RegexOption.IGNORE_CASE,
        ),
    )

    fun extract(turns: List<TomcTurn>): Extraction {
        val candidates = ArrayList<Entry>()
        var inputChars = 0
        turns.forEach { turn ->
            inputChars += turn.text.length
            // Controlled blocks are read only by parse(), never reinterpreted as new evidence.
            val raw = sanitize(turn.text)
            raw.lineSequence().map(String::trim).filter(String::isNotBlank).forEach { line ->
                task.find(line)?.let { match ->
                    candidates += Entry(
                        status = Status.PLANNED,
                        detail = match.groupValues[1].trim().trimEnd('。', '；', ';'),
                        sourceTurn = turn.index,
                        task = true,
                    )
                    return@forEach
                }
                for ((status, pattern) in lifecycle) {
                    val match = pattern.find(line) ?: continue
                    val detail = match.value.trim().trimStart('。', '；', ';').trim().trimEnd('。', '；', ';')
                    if (detail.length >= 4) {
                        candidates += Entry(status, detail, turn.index)
                    }
                    break
                }
            }
        }
        val merged = merge(emptyList(), candidates)
        return Extraction(merged, inputChars, merged.isNotEmpty())
    }

    /** Newer occurrences replace exact duplicates; contradictory lifecycle entries remain distinct. */
    fun merge(
        previous: List<Entry>,
        current: List<Entry>,
        limit: Int = DEFAULT_LIMIT,
    ): List<Entry> {
        val result = ArrayList<Entry>(previous + current)
        val deduplicated = LinkedHashMap<String, Entry>()
        result.forEach { entry ->
            val clean = entry.copy(detail = entry.detail.trim())
            if (clean.detail.isBlank()) return@forEach
            val key = "${clean.task}|${clean.status}|${clean.detail}"
            deduplicated.remove(key)
            deduplicated[key] = clean
        }
        return deduplicated.values.toList().takeLast(limit.coerceAtLeast(1))
    }

    /** Read back only the exact client-owned v1 syntax; malformed lines are ignored. */
    fun parse(summary: String): List<Entry> {
        val match = block.find(summary) ?: return emptyList()
        return match.value.lineSequence().mapNotNull { line ->
            val fields = splitEscaped(line.trim()) ?: return@mapNotNull null
            if (fields.size != 3 || !fields[0].startsWith("- ")) return@mapNotNull null
            val kind = fields[0].removePrefix("- ")
            val turn = fields[1].removePrefix("turn=").toIntOrNull() ?: return@mapNotNull null
            val detail = unescape(fields[2]).trim()
            if (detail.isBlank()) return@mapNotNull null
            if (kind == "task") {
                Entry(Status.PLANNED, detail, turn, task = true)
            } else {
                val status = Status.entries.firstOrNull { it.wire == kind } ?: return@mapNotNull null
                Entry(status, detail, turn)
            }
        }.toList()
    }

    /** Remove model-forged/orphaned controlled markup before composing the client-owned block. */
    fun sanitize(modelSummary: String): String = modelSummary
        .replace(block, "")
        .lineSequence()
        .filterNot { it.trim().let { line -> line == BEGIN || line == END } }
        .joinToString("\n")
        .trim()

    fun render(entries: List<Entry>, maxChars: Int): String? {
        if (entries.isEmpty() || maxChars <= BEGIN.length + END.length + 4) return null
        // Keep the most recent states when the deterministic budget is tight.
        for (start in entries.indices) {
            val body = entries.drop(start).joinToString("\n") { entry ->
                val kind = if (entry.task) "task" else entry.status.wire
                "- $kind|turn=${entry.sourceTurn}|${escape(entry.detail)}"
            }
            val rendered = "$BEGIN\n$body\n$END"
            if (rendered.length <= maxChars) return rendered
        }
        return null
    }

    /** Compose a summary and inherit earlier controlled blocks found in trusted summary turns. */
    fun compose(modelSummary: String, turns: List<TomcTurn>, maxChars: Int): String {
        val clean = sanitize(modelSummary)
        val previous = turns.filter { it.role == "summary" }.flatMap { parse(it.text) }
        val current = extract(turns).entries
        val ledger = render(merge(previous, current), maxChars)
        return listOfNotNull(clean.takeIf(String::isNotBlank), ledger).joinToString("\n\n")
    }

    private fun escape(value: String): String = value
        .replace("%", "%25")
        .replace("|", "%7C")
        .replace("\r", "%0D")
        .replace("\n", "%0A")

    private fun unescape(value: String): String = value
        .replace("%0A", "\n")
        .replace("%0D", "\r")
        .replace("%7C", "|")
        .replace("%25", "%")

    private fun splitEscaped(line: String): List<String>? {
        if (!line.startsWith("- ")) return null
        val fields = ArrayList<String>(3)
        var start = 0
        var index = 0
        while (index < line.length && fields.size < 2) {
            if (line[index] == '|' && (index == 0 || line[index - 1] != '%')) {
                fields += line.substring(start, index)
                start = index + 1
            }
            index++
        }
        fields += line.substring(start)
        return fields
    }
}
