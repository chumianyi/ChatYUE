package com.chatyue.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownSpeechStripperTest {
    @Test
    fun toSpeechText_keepsPlainTextAsIs() {
        val text = MarkdownSpeechStripper.toSpeechText("今天天气不错，适合出门散步。")

        assertEquals("今天天气不错，适合出门散步。", text)
    }

    @Test
    fun toSpeechText_removesHeadingAndEmphasisMarks() {
        val text = MarkdownSpeechStripper.toSpeechText("## 标题\n\n这是**重点**内容，还有*斜体*与~~删除~~。")

        assertEquals("标题\n\n这是重点内容，还有斜体与删除。", text)
    }

    @Test
    fun toSpeechText_removesCodeFencesAndLinks() {
        val markdown = """
            看这段代码：
            ```kotlin
            val x = 1
            ```
            参考 [官方文档](https://example.com) 与 `inline`。
        """.trimIndent()

        val text = MarkdownSpeechStripper.toSpeechText(markdown)

        assertTrue(!text.contains("```"))
        assertTrue(!text.contains("val x"))
        assertTrue(text.contains("官方文档"))
        // URL 与反引号都不该出现在朗读稿里。
        assertTrue(!text.contains("https://"))
        assertTrue(!text.contains("`"))
    }

    @Test
    fun toSpeechText_removesListQuotesAndRules() {
        val markdown = "> 引用一句\n- 第一项\n- 第二项\n\n---\n1. 有序一"

        val text = MarkdownSpeechStripper.toSpeechText(markdown)

        assertTrue(!text.contains("- "))
        assertTrue(!text.contains("1. "))
        assertTrue(!text.startsWith(">"))
        assertTrue(text.contains("引用一句"))
        assertTrue(text.contains("第一项"))
        assertTrue(text.contains("有序一"))
    }

    @Test
    fun toSpeechText_replacesTablePipes() {
        val markdown = "| 名称 | 数值 |\n| --- | --- |\n| 温度 | 25 |"

        val text = MarkdownSpeechStripper.toSpeechText(markdown)

        assertTrue(!text.contains("|"))
        assertTrue(!text.contains("、名称"))
        assertTrue(text.contains("名称 、 数值"))
        assertTrue(text.contains("温度 、 25"))
    }

    @Test
    fun toSpeechText_blankInputStaysBlank() {
        assertEquals("", MarkdownSpeechStripper.toSpeechText("   \n\n  "))
    }
}
