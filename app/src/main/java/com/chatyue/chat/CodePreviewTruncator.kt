package com.chatyue.chat

/**
 * 代码预览里「源码」视图的截断策略。
 *
 * 默认只展示前 [MAX_LINES] 行，其余行省略——
 * 但复制与下载永远取 [CodePreviewBlock.code] 的完整内容，不会被截断。
 */
object CodePreviewTruncator {
    const val MAX_LINES = 20

    data class TruncatedCode(
        val visible: String,
        val hiddenLineCount: Int,
        val truncated: Boolean,
    )

    fun firstLines(code: String, maxLines: Int = MAX_LINES): TruncatedCode {
        val lines = code.split("\n")
        return if (lines.size <= maxLines) {
            TruncatedCode(code, hiddenLineCount = 0, truncated = false)
        } else {
            val visible = lines.take(maxLines).joinToString("\n")
            TruncatedCode(visible, hiddenLineCount = lines.size - maxLines, truncated = true)
        }
    }
}
