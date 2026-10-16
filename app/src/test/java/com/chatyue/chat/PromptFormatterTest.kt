package com.chatyue.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptFormatterTest {
    @Test
    fun preview_collapsesWhitespace() {
        val content = "请把下面的内容\n翻译成\t英文，\n保留原有格式。"
        assertEquals("请把下面的内容 翻译成 英文， 保留原有格式。", PromptFormatter.preview(content))
    }

    @Test
    fun preview_trimsTrailingNewline() {
        // 指令正文常以换行结尾，摘要里不应残留尾部空白。
        assertEquals("中英互译说明", PromptFormatter.preview("中英互译说明\n"))
    }

    @Test
    fun preview_truncatesLongContent() {
        val content = "甲".repeat(100)
        val preview = PromptFormatter.preview(content)
        assertEquals(61, preview.length)
        assertTrue(preview.endsWith("…"))
        assertEquals("甲".repeat(60), preview.dropLast(1))
    }

    @Test
    fun preview_keepsShortContentUntouched() {
        assertEquals("简述", PromptFormatter.preview("简述"))
    }

    @Test
    fun isValid_rejectsBlankInput() {
        assertFalse(PromptFormatter.isValidTitle("   "))
        assertFalse(PromptFormatter.isValidContent(""))
        assertFalse(PromptFormatter.isValidContent("\n\t "))
        assertTrue(PromptFormatter.isValidTitle("  总结  "))
        assertTrue(PromptFormatter.isValidContent("请总结…"))
    }

    @Test
    fun normalize_trimsEdgesButKeepsInnerNewlines() {
        val (title, content) = PromptFormatter.normalize(
            "  润色  \n",
            "  第一行\n第二行\n  ",
        )
        assertEquals("润色", title)
        assertEquals("第一行\n第二行", content)
    }
}
