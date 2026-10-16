package com.chatyue.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageShareFormatterTest {
    private fun message(
        id: Long,
        role: MessageRole,
        content: String,
    ) = ChatMessage(
        id = id,
        conversationId = 1L,
        role = role,
        content = content,
        createdAt = 1,
    )

    @Test
    fun userMessage_getsRolePrefixAndSignature() {
        assertEquals(
            "我：今天天气怎么样\n\n—— 来自 ChatYUE",
            MessageShareFormatter.format(message(1, MessageRole.USER, "今天天气怎么样")),
        )
    }

    @Test
    fun assistantMessage_hasNoRolePrefix() {
        // 助手内容本身就是回答，不再加「AI：」前缀，读起来更自然。
        assertEquals(
            "晴，26℃\n\n—— 来自 ChatYUE",
            MessageShareFormatter.format(message(2, MessageRole.ASSISTANT, "晴，26℃")),
        )
    }

    @Test
    fun thinkingProcess_isNotShared() {
        val text = MessageShareFormatter.format(
            message(3, MessageRole.THINKING_FINISHED, "内心戏"),
        )
        assertEquals("—— 来自 ChatYUE", text)
    }

    @Test
    fun contentIsTrimmed() {
        assertEquals(
            "我：你好\n\n—— 来自 ChatYUE",
            MessageShareFormatter.format(message(4, MessageRole.USER, "  你好\n  ")),
        )
    }

    @Test
    fun formatAll_keepsOrderAndSkipsThinking() {
        val messages = listOf(
            message(1, MessageRole.USER, "问题"),
            message(2, MessageRole.THINKING_FINISHED, "内心戏"),
            message(3, MessageRole.ASSISTANT, "回答"),
        )
        // 多选分享按阅读顺序拼成一段对话，署名只出现在末尾一次。
        assertEquals(
            "我：问题\n\n回答\n\n—— 来自 ChatYUE",
            MessageShareFormatter.formatAll(messages),
        )
    }

    @Test
    fun formatAll_emptyWhenNothingShareable() {
        val messages = listOf(
            message(1, MessageRole.THINKING_ACTIVE, "思考中"),
            message(2, MessageRole.SYSTEM, "摘要"),
        )
        assertEquals("", MessageShareFormatter.formatAll(messages))
    }
}
