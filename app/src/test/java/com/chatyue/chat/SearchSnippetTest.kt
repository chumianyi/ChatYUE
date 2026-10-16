package com.chatyue.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SearchSnippetFactory] 的纯逻辑单测：截窗位置、省略号、命中区间与部分重叠裁剪。
 */
class SearchSnippetTest {

    @Test
    fun `短内容返回全文且无省略号`() {
        val snippet = SearchSnippetFactory.extract("你好呀", "你好")
        assertEquals("你好呀", snippet.text)
        assertFalse(snippet.hasLeadingEllipsis)
        assertFalse(snippet.hasTrailingEllipsis)
        assertTrue(snippet.matchRanges.isEmpty())
    }

    @Test
    fun `空关键词返回全文截断`() {
        val content = " ".repeat(200)
        val snippet = SearchSnippetFactory.extract(content, "   ")
        assertEquals(SearchSnippetFactory.DEFAULT_MAX_LENGTH, snippet.text.length)
        assertTrue(snippet.matchRanges.isEmpty())
        assertTrue(snippet.hasTrailingEllipsis)
        assertFalse(snippet.hasLeadingEllipsis)
    }

    @Test
    fun `未命中时从开头截断`() {
        val content = (1..300).joinToString("") { "甲" }
        val snippet = SearchSnippetFactory.extract(content, "乙", maxLength = 50)
        assertEquals(content.take(50), snippet.text)
        assertTrue(snippet.matchRanges.isEmpty())
        assertFalse(snippet.hasLeadingEllipsis)
        assertTrue(snippet.hasTrailingEllipsis)
    }

    @Test
    fun `关键词居中且两侧有省略号`() {
        val prefix = "前".repeat(80)
        val suffix = "后".repeat(80)
        val content = prefix + "目标词" + suffix
        val snippet = SearchSnippetFactory.extract(content, "目标词", maxLength = 40)
        assertTrue(snippet.hasLeadingEllipsis)
        assertTrue(snippet.hasTrailingEllipsis)
        assertEquals(40, snippet.text.length)
        assertTrue(snippet.text.contains("目标词"))
        // 唯一命中区间相对片段的位置就是词在片段中的下标。
        val range = snippet.matchRanges.single()
        val indexInText = snippet.text.indexOf("目标词")
        assertEquals(indexInText, range.first)
        assertEquals(indexInText + "目标词".length, range.last + 1)
    }

    @Test
    fun `命中在开头时只有后省略号`() {
        val content = "关键词" + "补".repeat(200)
        val snippet = SearchSnippetFactory.extract(content, "关键词", maxLength = 40)
        assertFalse(snippet.hasLeadingEllipsis)
        assertTrue(snippet.hasTrailingEllipsis)
        assertTrue(snippet.text.startsWith("关键词"))
        assertEquals(0, snippet.matchRanges.single().first)
        assertEquals("关键词".length, snippet.matchRanges.single().last + 1)
    }

    @Test
    fun `命中在结尾时只有前省略号`() {
        val content = "补".repeat(200) + "关键词"
        val snippet = SearchSnippetFactory.extract(content, "关键词", maxLength = 40)
        assertTrue(snippet.hasLeadingEllipsis)
        assertFalse(snippet.hasTrailingEllipsis)
        assertTrue(snippet.text.endsWith("关键词"))
    }

    @Test
    fun `大小写不敏感`() {
        val content = "甲ABC乙" + "丙".repeat(100)
        val snippet = SearchSnippetFactory.extract(content, "abc", maxLength = 40)
        assertEquals(1, snippet.matchRanges.size)
        assertEquals("ABC", snippet.text.substring(snippet.matchRanges.single()))
        assertTrue(snippet.text.startsWith("甲ABC"))
    }

    @Test
    fun `窗口内多次命中都有区间`() {
        val content = "甲abc乙abc" + "丙".repeat(100)
        val snippet = SearchSnippetFactory.extract(content, "abc", maxLength = 90)
        assertEquals(2, snippet.matchRanges.size)
        snippet.matchRanges.forEach { range ->
            assertEquals("abc", snippet.text.substring(range).lowercase())
        }
    }

    @Test
    fun `窗口外的命中不产出区间`() {
        val content = "abc" + "x".repeat(100) + "ABC" + "y".repeat(50) + "aBc"
        val snippet = SearchSnippetFactory.extract(content, "abc", maxLength = 50)
        // 首个命中在开头，窗口只覆盖前 50 个字符，后两次命中被裁掉。
        assertEquals(listOf(0 until 3), snippet.matchRanges)
        assertFalse(snippet.hasLeadingEllipsis)
        assertTrue(snippet.hasTrailingEllipsis)
    }

    @Test
    fun `长关键词超出窗口时高亮可见部分`() {
        val keyword = "超长搜索词测试用例"
        val content = "补".repeat(60) + keyword + "补".repeat(60)
        // 窗口 10 个字：前 5 个上下文 + 命中词头 5 个字。
        val snippet = SearchSnippetFactory.extract(content, keyword, maxLength = 10)
        assertEquals(10, snippet.text.length)
        assertEquals(listOf(5 until 10), snippet.matchRanges)
        assertEquals("超长搜索词", snippet.text.substring(snippet.matchRanges.single()))
    }

    @Test
    fun `非正 maxLength 返回空片段`() {
        val snippet = SearchSnippetFactory.extract("任意内容", "任意", maxLength = 0)
        assertEquals("", snippet.text)
        assertTrue(snippet.matchRanges.isEmpty())
        assertFalse(snippet.hasLeadingEllipsis)
        assertFalse(snippet.hasTrailingEllipsis)
    }
}

