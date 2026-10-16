package com.chatyue.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [GenerationNotifier] 文案构造的纯逻辑单测。
 */
class GenerationNotifierTest {

    @Test
    fun `短回复原样作为预览`() {
        assertEquals("好的，这是一条简短回复。", GenerationNotifier.preview("好的，这是一条简短回复。"))
    }

    @Test
    fun `长回复截断并补省略号`() {
        val content = "回复".repeat(100)
        val preview = GenerationNotifier.preview(content, maxLength = 20)
        assertEquals("回复".repeat(10) + "…", preview)
        assertEquals(21, preview.length)
    }

    @Test
    fun `空白与换行被压成单空格`() {
        val preview = GenerationNotifier.preview("第一行\n\n第二行\t\t第三行")
        assertEquals("第一行 第二行 第三行", preview)
    }

    @Test
    fun `纯空白回复给占位文案`() {
        assertEquals("（回复内容为空）", GenerationNotifier.preview("   \n\t  "))
    }

    @Test
    fun `截断点落在空白上时不保留尾部空格`() {
        val preview = GenerationNotifier.preview("一二三四五六 七八九十", maxLength = 7)
        assertEquals("一二三四五六…", preview)
    }

    @Test
    fun `标题带会话名与完成标识`() {
        assertEquals("周报怎么写 · 回复完成", GenerationNotifier.title("周报怎么写"))
    }

    @Test
    fun `超长会话标题按侧栏规则截断`() {
        val title = "这是一个特别特别特别长的会话标题需要被截断处理"
        val expected = ConversationFormatter.titleFrom(title) + " · 回复完成"
        assertEquals(expected, GenerationNotifier.title(title))
        assertEquals(20 + 7, expected.length)
    }

    @Test
    fun `空会话标题退回新对话`() {
        assertEquals("新对话 · 回复完成", GenerationNotifier.title("   "))
    }
}
