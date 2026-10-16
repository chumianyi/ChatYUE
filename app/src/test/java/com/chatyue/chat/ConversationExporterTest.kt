package com.chatyue.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationExporterTest {
    private fun message(
        id: Long,
        role: MessageRole,
        content: String,
        repliedToId: Long? = null,
        repliedRole: MessageRole? = null,
        repliedPreview: String? = null,
    ) = ChatMessage(
        id = id,
        conversationId = 1L,
        role = role,
        content = content,
        createdAt = 1_700_000_000_000L,
        repliedToId = repliedToId,
        repliedRole = repliedRole,
        repliedPreview = repliedPreview,
    )

    private val conversation = Conversation(
        id = 1L,
        title = "测试会话",
        updatedAt = 1_700_000_000_000L,
    )

    @Test
    fun toMarkdown_containsTitleSpeakersAndContent() {
        val messages = listOf(
            message(1L, MessageRole.USER, "你好"),
            message(2L, MessageRole.ASSISTANT, "你好，有什么可以帮你？"),
        )

        val markdown = ConversationExporter.toMarkdown(conversation, messages)

        assertTrue(markdown.startsWith("# 测试会话"))
        assertTrue(markdown.contains("**我**"))
        assertTrue(markdown.contains("**ChatYUE**"))
        assertTrue(markdown.contains("你好，有什么可以帮你？"))
        assertTrue(markdown.contains("共 2 条消息"))
    }

    @Test
    fun toMarkdown_skipsStreamingThinkingMessages() {
        val messages = listOf(
            message(1L, MessageRole.THINKING_FINISHED, "这是思考过程"),
            message(2L, MessageRole.ASSISTANT, "正式回答"),
        )

        val markdown = ConversationExporter.toMarkdown(conversation, messages)

        assertFalse(markdown.contains("这是思考过程"))
        assertTrue(markdown.contains("共 1 条消息"))
    }

    @Test
    fun toMarkdown_writesQuoteLineForRepliedMessage() {
        val messages = listOf(
            message(1L, MessageRole.USER, "帮我总结这篇文章"),
            message(
                id = 2L,
                role = MessageRole.ASSISTANT,
                content = "这是总结",
                repliedToId = 1L,
                repliedRole = MessageRole.USER,
                repliedPreview = "帮我总结这篇文章",
            ),
        )

        val markdown = ConversationExporter.toMarkdown(conversation, messages)

        assertTrue(markdown.contains("> 引用我：帮我总结这篇文章"))
    }

    @Test
    fun toPlainText_keepsPlainStructureWithoutMarkdownSymbols() {
        val messages = listOf(
            message(1L, MessageRole.USER, "第一句"),
            message(2L, MessageRole.ASSISTANT, "第二句"),
        )

        val text = ConversationExporter.toPlainText(conversation, messages)

        assertFalse(text.contains("**"))
        assertTrue(text.contains("我 · "))
        assertTrue(text.contains("ChatYUE · "))
        assertTrue(text.contains("第一句"))
        assertTrue(text.contains("第二句"))
    }

    @Test
    fun fileNameFor_sanitizesIllegalCharactersAndLimitsLength() {
        val weird = conversation.copy(title = "a/b:c*d?e\"f<g>h|i\\j\nk")

        val fileName = ConversationExporter.fileNameFor(weird, "md")

        assertTrue(fileName.endsWith(".md"))
        assertFalse(fileName.contains("/"))
        assertTrue(fileName.startsWith("a_b_c_d_e_f_g_h_i_j"))
    }

    @Test
    fun fileNameFor_fallsBackWhenTitleBlank() {
        val blank = conversation.copy(title = "   ")

        assertEquals("会话导出.md", ConversationExporter.fileNameFor(blank, "md"))
    }
}
