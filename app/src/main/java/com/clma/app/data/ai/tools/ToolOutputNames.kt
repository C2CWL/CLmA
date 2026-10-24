package com.clma.app.data.ai.tools

/**
 * 工具输出落盘文件名的唯一来源。
 *
 * 文件名由 toolCallId 派生，而 toolCallId 是模型/供应商给的。直接
 * `File(dir, "$toolCallId.txt")` 时，`../../shared_prefs/x` 或 `a/b` 这样的 id 会把内容
 * 写到 tool_outputs/ 之外。写入侧（以及将来的回读侧）共用同一个函数，才不会就"这个名字
 * 对应哪个文件"产生分歧。
 *
 * 消毒是刻意有损的：调用方只需要一个可以原样引用的句柄，不需要这个 id 的可逆编码。
 */
object ToolOutputNames {

    const val EXTENSION = ".txt"

    /** 一个供应商 tool-call id 加扩展名足够长；更长的名字说明它不是 id。 */
    const val MAX_NAME_CHARS = 64

    private val UNSAFE = Regex("[^A-Za-z0-9._-]")

    /**
     * [toolCallId] 对应的落盘文件名：同一个 id 永远得到同一个名字，并且永远是一个位于
     * tool_outputs/ 内的普通文件名（不含分隔符、不以点开头、长度受限）。
     */
    fun fileNameFor(toolCallId: String): String {
        val stem = UNSAFE.replace(toolCallId, "_")
            .trimStart('.')
            .take(MAX_NAME_CHARS - EXTENSION.length)
            .trimEnd('.')
            .ifBlank { "output" }
        return stem + EXTENSION
    }
}
