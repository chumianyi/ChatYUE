package com.chatyue.chat

interface ChatRepository {
    /**
     * 新建会话。
     *
     * @param memoryMode 记忆模式：全局对话接入全域记忆，单独对话独立隔离。
     */
    fun createConversation(
        title: String,
        memoryMode: ConversationMemoryMode = ConversationMemoryMode.GLOBAL,
    ): Long

    fun updateConversationTitle(conversationId: Long, title: String)

    fun updateConversationMemoryMode(
        conversationId: Long,
        memoryMode: ConversationMemoryMode,
    )

    fun getConversations(query: String = ""): List<Conversation>

    fun getConversation(conversationId: Long): Conversation?

    fun getMessages(conversationId: Long): List<ChatMessage>

    /**
     * 全局记忆：取其他所有「全局对话」会话的消息，按时间正序。
     *
     * 单独对话模式产生的消息不会进入全局记忆，因此不在此结果中。
     */
    fun getGlobalMemoryMessages(excludeConversationId: Long): List<ChatMessage>

    /**
     * 追加一条消息。
     *
     * @param repliedToId 这条消息引用的消息 id；null 表示不引用。
     * @param repliedRole 被引用消息的角色，与 [repliedToId] 同时提供。
     * @param repliedPreview 被引用内容的摘要预览，与 [repliedToId] 同时提供。
     */
    fun addMessage(
        conversationId: Long,
        role: MessageRole,
        content: String,
        repliedToId: Long? = null,
        repliedRole: MessageRole? = null,
        repliedPreview: String? = null,
    ): Long

    fun updateMessageRole(messageId: Long, role: MessageRole)

    /** 删除单条消息。 */
    fun deleteMessage(messageId: Long)

    /**
     * 删除给定消息之后（不含该消息本身）的全部消息，用于“重新生成”。
     *
     * 以该消息作为最后一条用户提问，后续由调用方重新发起请求。
     */
    fun deleteMessagesAfter(messageId: Long)

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

    /** 置顶 / 取消置顶会话。 */
    fun setConversationPinned(conversationId: Long, pinned: Boolean)

    /** 删除会话及其全部消息，返回被删除会话的标题（供提示使用）。 */
    fun deleteConversation(conversationId: Long): String?

    /**
     * 跨会话搜索消息正文（仅用户与助手消息），按时间倒序返回命中列表。
     *
     * @param query 关键词，空字符串返回空列表。
     * @param limit 最多返回的条数。
     */
    fun searchMessages(query: String, limit: Int = 100): List<MessageHit>
}
