package com.chatyue.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [EditMessageText] 拆分 / 重组的纯逻辑单测。
 */
class EditMessageTextTest {

    @Test
    fun `纯正文没有附件块`() {
        val parts = EditMessageText.split("今天天气怎么样")
        assertEquals("今天天气怎么样", parts.body)
        assertEquals("", parts.attachmentBlock)
    }

    @Test
    fun `单图附件块被完整切出`() {
        val content = "帮我看看这张图\n\n[图片附件] photo.jpg（本模型仅支持识别文本，无法识别图片内容）"
        val parts = EditMessageText.split(content)
        assertEquals("帮我看看这张图", parts.body)
        assertEquals(
            "\n\n[图片附件] photo.jpg（本模型仅支持识别文本，无法识别图片内容）",
            parts.attachmentBlock,
        )
    }

    @Test
    fun `多图附件块按列表切出`() {
        val content = "三张图\n\n[图片附件] 共 3 张：\n1、a.png\n2、b.png\n3、c.png（本模型仅支持识别文本，无法识别图片内容）"
        val parts = EditMessageText.split(content)
        assertEquals("三张图", parts.body)
        assertEquals(content, parts.body + parts.attachmentBlock)
    }

    @Test
    fun `图片与文本附件同时存在时以最靠前标记切开`() {
        val content = "正文\n\n[图片附件] a.png（本模型仅支持识别文本，无法识别图片内容）" +
            "\n\n[文本附件] notes.txt\n内容\n[/文本附件]"
        val parts = EditMessageText.split(content)
        assertEquals("正文", parts.body)
        // 两个块都留在附件块里，重组时不会丢。
        assertEquals(content, parts.body + parts.attachmentBlock)
    }

    @Test
    fun `文本附件单独存在`() {
        val content = "读一下\n\n[文本附件] notes.txt\n内容\n[/文本附件]"
        val parts = EditMessageText.split(content)
        assertEquals("读一下", parts.body)
        assertEquals("\n\n[文本附件] notes.txt\n内容\n[/文本附件]", parts.attachmentBlock)
    }

    @Test
    fun `正文为空时只保留附件块`() {
        val parts = EditMessageText.split("\n\n[图片附件] a.png（本模型仅支持识别文本，无法识别图片内容）")
        assertEquals("", parts.body)
        val merged = EditMessageText.merge("", parts.attachmentBlock)
        assertEquals("[图片附件] a.png（本模型仅支持识别文本，无法识别图片内容）", merged)
    }

    @Test
    fun `合并时正文去空白且附件块前导换行保留`() {
        val merged = EditMessageText.merge("  改过的正文  ", "\n\n[图片附件] a.png（本模型仅支持识别文本，无法识别图片内容）")
        assertEquals("改过的正文\n\n[图片附件] a.png（本模型仅支持识别文本，无法识别图片内容）", merged)
    }

    @Test
    fun `没有附件块时合并即去空白正文`() {
        assertEquals("只有正文", EditMessageText.merge("  只有正文 ", ""))
    }

    @Test
    fun `拆分重组往返后内容不变`() {
        val content = "原始提问\n\n[图片附件] 共 2 张：\n1、a.png\n2、b.png（本模型仅支持识别文本，无法识别图片内容）"
        val parts = EditMessageText.split(content)
        assertEquals(content, EditMessageText.merge(parts.body, parts.attachmentBlock))
    }

    @Test
    fun `正文里出现裸标记但不带前导换行时不算附件块`() {
        // 用户手动在正文里写了「[图片附件]」字样且前后不是换行，按正文处理。
        val content = "规则是[图片附件]开头要加换行"
        val parts = EditMessageText.split(content)
        assertEquals(content, parts.body)
        assertEquals("", parts.attachmentBlock)
    }
}
