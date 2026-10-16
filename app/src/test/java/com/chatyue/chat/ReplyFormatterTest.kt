package com.chatyue.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ReplyFormatterTest {
    @Test
    fun preview_collapsesWhitespaceIntoSingleLine() {
        val preview = ReplyFormatter.preview("  第一行\n\n  第二行\t\t结尾  ")

        assertEquals("第一行 第二行 结尾", preview)
    }

    @Test
    fun preview_keepsShortContentAsIs() {
        val preview = ReplyFormatter.preview("这是一条很短的被引用消息")

        assertEquals("这是一条很短的被引用消息", preview)
    }

    @Test
    fun preview_truncatesLongContentWithEllipsis() {
        // 90 个字符，超过 80 上限，应截断到 80 并追加省略号。
        val long = "消".repeat(90)

        val preview = ReplyFormatter.preview(long)

        assertEquals("消".repeat(80) + "…", preview)
    }

    @Test
    fun preview_blankContentBecomesEmpty() {
        assertEquals("", ReplyFormatter.preview("   \n\t  "))
    }
}
