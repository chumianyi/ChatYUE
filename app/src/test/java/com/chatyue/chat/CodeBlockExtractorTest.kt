package com.chatyue.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeBlockExtractorTest {

    @Test
    fun `提取单个 HTML 块并移出正文`() {
        val content = "看这段代码：\n```html\n<div>hi</div>\n```\n结束"
        val result = CodeBlockExtractor.extract(content)
        assertEquals(1, result.blocks.size)
        val block = result.blocks[0]
        assertEquals("html", block.language)
        assertEquals("<div>hi</div>", block.code)
        assertTrue(block.previewable)
        assertTrue(block.isHtml)
        assertFalse(block.isPlainCode)
        assertEquals("看这段代码：\n结束", result.remainingText)
    }

    @Test
    fun `md 与 markdown 都算可预览 Markdown`() {
        val md = CodeBlockExtractor.extract("```md\n# 标题\n```")
        assertEquals(1, md.blocks.size)
        assertTrue(md.blocks[0].previewable)
        assertFalse(md.blocks[0].isHtml)
        assertTrue(md.blocks[0].isMarkdown)

        val markdown = CodeBlockExtractor.extract("```markdown\n# 标题\n```")
        assertEquals(1, markdown.blocks.size)
        assertTrue(markdown.blocks[0].isMarkdown)
    }

    @Test
    fun `普通语言被提取为可复制代码块而非预览`() {
        val content = "看这个：\n```python\nprint(1)\n```\n完"
        val result = CodeBlockExtractor.extract(content)
        assertEquals(1, result.blocks.size)
        val block = result.blocks[0]
        assertEquals("python", block.language)
        assertEquals("print(1)", block.code)
        assertFalse(block.previewable)
        assertTrue(block.isPlainCode)
        assertEquals("看这个：\n完", result.remainingText)
    }

    @Test
    fun `多个代码块全部提取并移出正文`() {
        val content = "```html\n<a>1</a>\n```\n中间\n```json\n{}\n```"
        val result = CodeBlockExtractor.extract(content)
        assertEquals(2, result.blocks.size)
        assertEquals("html", result.blocks[0].language)
        assertEquals("json", result.blocks[1].language)
        assertFalse(result.blocks[1].previewable)
        assertEquals("中间", result.remainingText)
    }

    @Test
    fun `空内容返回原样与空块列表`() {
        val result = CodeBlockExtractor.extract("")
        assertEquals("", result.remainingText)
        assertTrue(result.blocks.isEmpty())
    }

    @Test
    fun `抽掉块后的大片空行被压缩`() {
        val content = "前文\n\n```html\nx\n```\n\n\n\n后文"
        val result = CodeBlockExtractor.extract(content)
        assertEquals("前文\n后文", result.remainingText)
    }

    @Test
    fun `不区分大小写识别语言`() {
        val result = CodeBlockExtractor.extract("```HTML\n<b>1</b>\n```")
        assertEquals(1, result.blocks.size)
        assertTrue(result.blocks[0].isHtml)
    }
}
