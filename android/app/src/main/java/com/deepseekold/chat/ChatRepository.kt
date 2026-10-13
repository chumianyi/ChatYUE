package com.deepseekold.chat

interface ChatRepository {
    fun createConversation(title: String): Long

    fun updateConversationTitle(conversationId: Long, title: String)

    fun getConversations(query: String = ""): List<Conversation>

    fun getMessages(conversationId: Long): List<ChatMessage>

    fun addMessage(
        conversationId: Long,
        role: MessageRole,
        content: String,
    ): Long

    fun updateMessageRole(messageId: Long, role: MessageRole)

    fun finalizeInterruptedThinking()

    /**
     * 上下文压缩：删除被压缩掉的消息，并在会话最前面插入一条 system 摘要。
     */
    fun replaceMessagesWithSystemSummary(
        conversationId: Long,
        removedMessageIds: List<Long>,
        summary: String,
    )

    fun clearAll()
}
