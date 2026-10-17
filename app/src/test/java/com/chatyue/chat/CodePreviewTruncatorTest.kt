package com.chatyue.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodePreviewTruncatorTest {

    @Test
    fun `不足上限不截断`() {
        val code = (1..10).joinToString("\n") { "line$it" }
        val result = CodePreviewTruncator.firstLines(code)
        assertFalse(result.truncated)
        assertEquals(0, result.hiddenLineCount)
        assertEquals(code, result.visible)
    }

    @Test
    fun `恰好等于上限不截断`() {
        val code = (1..20).joinToString("\n") { "line$it" }
        val result = CodePreviewTruncator.firstLines(code)
        assertFalse(result.truncated)
        assertEquals(0, result.hiddenLineCount)
        assertEquals(code, result.visible)
    }

    @Test
    fun `超过上限只留前 20 行并报告省略行数`() {
        val code = (1..50).joinToString("\n") { "line$it" }
        val result = CodePreviewTruncator.firstLines(code)
        assertTrue(result.truncated)
        assertEquals(30, result.hiddenLineCount)
        val expected = (1..20).joinToString("\n") { "line$it" }
        assertEquals(expected, result.visible)
    }

    @Test
    fun `空字符串不截断`() {
        val result = CodePreviewTruncator.firstLines("")
        assertFalse(result.truncated)
        assertEquals("", result.visible)
    }
}
