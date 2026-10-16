package com.chatyue.chat

/**
 * 把 Markdown 正文清洗成适合朗读的纯文本。
 *
 * 代码块、行内代码、图片语法等对语音无意义甚至有害，统一剔除；
 * 标题 / 列表 / 引用 / 粗斜体等记号只保留其文字内容。
 */
object MarkdownSpeechStripper {
    private val FENCED_CODE = Regex("```[\\s\\S]*?```")
    private val INLINE_CODE = Regex("`([^`]*)`")
    private val IMAGE = Regex("!\\[[^]]*]\\([^)]*\\)")
    private val LINK = Regex("\\[([^]]*)]\\([^)]*\\)")
    private val HEADING = Regex("(?m)^#{1,6}\\s*")
    private val BLOCKQUOTE = Regex("(?m)^>\\s?")
    private val LIST_MARKER = Regex("(?m)^\\s*([-*+]|\\d+[.)])\\s+")
    private val TABLE_SEPARATOR = Regex("(?m)^\\s*\\|?[\\s:|-]+\\|?\\s*$")
    private val TABLE_LEADING_PIPE = Regex("(?m)^\\s*\\|")
    private val TABLE_TRAILING_PIPE = Regex("(?m)\\|\\s*$")
    private val EMPHASIS = Regex("(\\*\\*|__|\\*|_|~~)")
    private val HORIZONTAL_RULE = Regex("(?m)^\\s*(-{3,}|\\*{3,}|_{3,})\\s*$")
    private val BLANK_LINES = Regex("\\n{3,}")

    fun toSpeechText(markdown: String): String {
        var text = markdown
        text = FENCED_CODE.replace(text, "")
        text = IMAGE.replace(text, "")
        text = INLINE_CODE.replace(text, "$1")
        text = LINK.replace(text, "$1")
        text = TABLE_SEPARATOR.replace(text, "")
        text = TABLE_LEADING_PIPE.replace(text, "")
        text = TABLE_TRAILING_PIPE.replace(text, "")
        text = HEADING.replace(text, "")
        text = BLOCKQUOTE.replace(text, "")
        text = LIST_MARKER.replace(text, "")
        text = HORIZONTAL_RULE.replace(text, "")
        text = EMPHASIS.replace(text, "")
        // 表格单元格之间的竖线读起来全是噪音，换成顿号。
        text = text.replace("|", "、")
        text = BLANK_LINES.replace(text, "\n\n")
        return text.trim()
    }
}
