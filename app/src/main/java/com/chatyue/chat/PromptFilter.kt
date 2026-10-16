package com.chatyue.chat

/**
 * 快捷指令库的本地过滤（2.0 新增）。
 *
 * 指令数量到几十条以后，翻找就成了体力活：标题或正文任一包含关键词
 * 即视为命中。纯函数实现，不依赖 Android 框架，便于单元测试。
 */
object PromptFilter {

    /**
     * 判断一条指令是否匹配 [query]。
     *
     * 关键词去首尾空白后为空表示不过滤（全部命中）；
     * 大小写不敏感，标题与正文都参与匹配。
     */
    fun matches(title: String, content: String, query: String): Boolean {
        val keyword = query.trim()
        if (keyword.isEmpty()) return true
        return title.contains(keyword, ignoreCase = true) ||
            content.contains(keyword, ignoreCase = true)
    }

    /** 过滤整个列表，保持原有顺序。 */
    fun filter(prompts: List<Prompt>, query: String): List<Prompt> =
        prompts.filter { matches(it.title, it.content, query) }
}
