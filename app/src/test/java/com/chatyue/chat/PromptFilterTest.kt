package com.chatyue.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PromptFilter] 的纯逻辑单测。
 */
class PromptFilterTest {

    private fun prompt(title: String, content: String) = Prompt(
        id = 1L,
        title = title,
        content = content,
        createdAt = 0L,
    )

    @Test
    fun `空关键词全部命中`() {
        assertTrue(PromptFilter.matches("周报", "帮我写周报", ""))
        assertTrue(PromptFilter.matches("周报", "帮我写周报", "   "))
    }

    @Test
    fun `标题命中`() {
        assertTrue(PromptFilter.matches("周报助手", "随便写点什么", "周报"))
    }

    @Test
    fun `正文命中`() {
        assertTrue(PromptFilter.matches("助手", "请帮我写一份周报", "周报"))
    }

    @Test
    fun `都不命中`() {
        assertFalse(PromptFilter.matches("助手", "随便写点什么", "周报"))
    }

    @Test
    fun `大小写不敏感`() {
        assertTrue(PromptFilter.matches("SQL 优化", "optimize my SQL", "sql"))
        assertTrue(PromptFilter.matches("翻译", "Translate to English", "translate"))
    }

    @Test
    fun `关键词首尾空白被忽略`() {
        assertTrue(PromptFilter.matches("周报助手", "正文", "  周报  "))
    }

    @Test
    fun `过滤保持原顺序`() {
        val prompts = listOf(
            prompt("翻译", "translate"),
            prompt("周报", "weekly report"),
            prompt("总结", "summarize 周报"),
        )
        val filtered = PromptFilter.filter(prompts, "周报")
        assertEquals(listOf("周报", "总结"), filtered.map { it.title })
    }

    @Test
    fun `空查询返回原列表`() {
        val prompts = listOf(prompt("甲", "a"), prompt("乙", "b"))
        assertEquals(prompts, PromptFilter.filter(prompts, "  "))
    }
}
