package com.chatyue.chat

/**
 * 从消息正文里挑出围栏代码块，统一渲染成可复制的卡片。
 *
 * - 预览型：`html` / `md` / `markdown`，能在 App 内直接渲染成预览；
 * - 普通型：其余语言（python、json 等），只展示代码并提供复制/下载。
 *
 * 无论哪种，被提取的代码块都会从正文里移除，避免同一段代码既出现在卡片、
 * 又原样留在正文造成重复。
 */
data class CodePreviewBlock(
    val language: String,
    val code: String,
    val previewable: Boolean,
) {
    val isHtml: Boolean get() = previewable && language == "html"
    val isMarkdown: Boolean get() = previewable && language != "html"
    /** 普通型（不可预览）只展示代码，不提供预览切换。 */
    val isPlainCode: Boolean get() = !previewable
}

data class CodeBlockExtraction(
    val remainingText: String,
    val blocks: List<CodePreviewBlock>,
)

object CodeBlockExtractor {
    private val FENCE = Regex("(?i)```([a-zA-Z0-9_+#-]+)[ \\t]*\\n([\\s\\S]*?)```")
    private val PREVIEWABLE = setOf("html", "md", "markdown")

    /** 该语言是否支持预览（不区分大小写）。 */
    fun supports(language: String): Boolean =
        language.trim().lowercase() in PREVIEWABLE

    fun extract(content: String): CodeBlockExtraction {
        if (content.isBlank()) {
            return CodeBlockExtraction(content, emptyList())
        }
        val blocks = mutableListOf<CodePreviewBlock>()
        val remaining = FENCE.replace(content) { m ->
            val language = m.groupValues[1].lowercase()
            val code = m.groupValues[2].removeSuffix("\n")
            blocks.add(
                CodePreviewBlock(
                    language = language,
                    code = code,
                    previewable = supports(language),
                ),
            )
            "" // 提取出的块从正文移除
        }
        val finalText = if (blocks.isEmpty()) remaining else compactBlankLines(remaining)
        return CodeBlockExtraction(finalText, blocks)
    }

    /** 抽掉代码块后可能遗留的连续换行，压成单个换行，避免正文出现多余空行。 */
    private fun compactBlankLines(text: String): String =
        text.replace(Regex("\\n{2,}"), "\n").trim()
}
