package com.chatyue.chat

/** 引用回复场景下的文本格式化。 */
object ReplyFormatter {
    private const val MAX_PREVIEW_LENGTH = 80

    /**
     * 把被引用内容压成一行预览：折叠所有空白、超长截断。
     *
     * 用于输入区预览条与气泡内引用块，两边共用同一套摘要规则。
     */
    fun preview(content: String): String {
        val collapsed = content.trim().replace(Regex("\\s+"), " ")
        return if (collapsed.length <= MAX_PREVIEW_LENGTH) {
            collapsed
        } else {
            collapsed.take(MAX_PREVIEW_LENGTH) + "…"
        }
    }
}
