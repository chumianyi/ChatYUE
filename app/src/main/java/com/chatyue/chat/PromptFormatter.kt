package com.chatyue.chat

/**
 * 快捷指令的展示与校验工具（纯函数，便于单测）。
 */
object PromptFormatter {

    /** 列表里正文摘要的最大长度。 */
    private const val PREVIEW_LIMIT = 60

    /**
     * 生成正文摘要：折叠所有连续空白为单个空格，超长时截断并加省略号。
     * 指令正文常以换行结尾，折叠后尾部空格也会被清掉。
     */
    fun preview(content: String): String {
        val collapsed = content
            .replace(Regex("\\s+"), " ")
            .trim()
        if (collapsed.length <= PREVIEW_LIMIT) return collapsed
        return collapsed.take(PREVIEW_LIMIT).trimEnd() + "…"
    }

    /** 标题是否可提交：去掉首尾空白后非空。 */
    fun isValidTitle(title: String): Boolean = title.isNotBlank()

    /** 正文是否可提交：去掉首尾空白后非空。 */
    fun isValidContent(content: String): Boolean = content.isNotBlank()

    /**
     * 规范化待保存的指令：去掉首尾空白，但保留正文内部原有的换行结构。
     */
    fun normalize(title: String, content: String): Pair<String, String> =
        title.trim() to content.trim()
}
