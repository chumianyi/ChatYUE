package com.chatyue.chat

/**
 * 跨会话消息搜索的结果片段。
 *
 * 搜索结果列表里不适合直接铺全文：长消息会把列表撑得很乱，
 * 也难以一眼看到命中的关键词。这里以首个命中位置为中心截窗，
 * 并给出所有命中区间，供列表项做高亮渲染。
 *
 * 纯逻辑、不依赖 Android，便于单测。
 */
data class SearchSnippet(
    /** 截窗后的正文片段，不含省略号；省略号由 [hasLeadingEllipsis] / [hasTrailingEllipsis] 表达。 */
    val text: String,
    /**
     * 命中的关键词区间，半开语义（`start until start + keyword.length`），下标相对于 [text]。
     * 与窗口仅部分重叠的命中会按交集保留，搜索词比窗口还长时也能高亮可见部分。
     */
    val matchRanges: List<IntRange>,
    /** 片段之前还有正文（渲染时应在开头补省略号）。 */
    val hasLeadingEllipsis: Boolean,
    /** 片段之后还有正文（渲染时应在末尾补省略号）。 */
    val hasTrailingEllipsis: Boolean,
)

/** [SearchSnippet] 的构造工具。 */
object SearchSnippetFactory {

    /** 搜索结果摘要的默认截窗长度，约等于三行正文。 */
    const val DEFAULT_MAX_LENGTH = 90

    /**
     * 以 [keyword] 的首个命中位置为中心，从 [content] 中截取长度不超过 [maxLength] 的片段。
     *
     * - 关键词为空 / 内容比 [maxLength] 短：返回全文，无高亮区间；
     * - 内容不含关键词：从开头截断，无高亮区间；
     * - 关键词大小写不敏感，与搜索页 SQLite `LIKE` 的行为保持一致；
     * - [maxLength] <= 0 时返回空片段。
     */
    fun extract(
        content: String,
        keyword: String,
        maxLength: Int = DEFAULT_MAX_LENGTH,
    ): SearchSnippet {
        if (maxLength <= 0) {
            return SearchSnippet(text = "", matchRanges = emptyList(), hasLeadingEllipsis = false, hasTrailingEllipsis = false)
        }
        val normalized = keyword.trim()
        if (content.length <= maxLength) {
            return SearchSnippet(
                text = content,
                matchRanges = emptyList(),
                hasLeadingEllipsis = false,
                hasTrailingEllipsis = false,
            )
        }
        val matches = if (normalized.isEmpty()) emptyList() else findAllMatches(content, normalized)
        // 未命中：回到开头截断，让用户看到消息是怎么开始的。
        if (matches.isEmpty()) {
            return SearchSnippet(
                text = content.take(maxLength),
                matchRanges = emptyList(),
                hasLeadingEllipsis = false,
                hasTrailingEllipsis = true,
            )
        }
        val firstMatchStart = matches.first().first
        var windowStart = (firstMatchStart - maxLength / 2).coerceAtLeast(0)
        var windowEnd = windowStart + maxLength
        // 窗口右端触底时回退起点，保证片段长度仍是满的。
        if (windowEnd > content.length) {
            windowEnd = content.length
            windowStart = (windowEnd - maxLength).coerceAtLeast(0)
        }
        // 窗口起点最多落在首个命中处，不会把命中挡在窗外。
        if (windowStart > firstMatchStart) windowStart = firstMatchStart
        val text = content.substring(windowStart, windowEnd)
        val matchRanges = matches.mapNotNull { range ->
            val start = maxOf(range.first, windowStart)
            val endExclusive = minOf(range.last + 1, windowEnd)
            if (start >= endExclusive) {
                null
            } else {
                (start - windowStart) until (endExclusive - windowStart)
            }
        }
        return SearchSnippet(
            text = text,
            matchRanges = matchRanges,
            hasLeadingEllipsis = windowStart > 0,
            hasTrailingEllipsis = windowEnd < content.length,
        )
    }

    /** 找出全部命中位置（大小写不敏感），按出现顺序返回半开区间。 */
    private fun findAllMatches(content: String, keyword: String): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var from = 0
        while (true) {
            val index = content.indexOf(keyword, startIndex = from, ignoreCase = true)
            if (index < 0) break
            ranges += index until (index + keyword.length)
            from = index + keyword.length
            if (from >= content.length) break
        }
        return ranges
    }
}
