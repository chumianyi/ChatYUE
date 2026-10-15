package com.chatyue.chat

data class Conversation(
    val id: Long,
    val title: String,
    val updatedAt: Long,
)

enum class MessageRole {
    /** 用户消息。 */
    USER,

    /** 模型正式回答。 */
    ASSISTANT,

    /** 正在流式输出的思考过程。 */
    THINKING_ACTIVE,

    /** 已完成的思考过程。 */
    THINKING_FINISHED,

    /** 上下文自动压缩后生成的摘要，会作为 system 消息参与后续请求。 */
    SYSTEM,
}

data class ChatMessage(
    val id: Long,
    val conversationId: Long,
    val role: MessageRole,
    val content: String,
    val createdAt: Long,
)

/** 侧边栏会话按时间由近到远分组。 */
enum class ConversationSection {
    TODAY,
    YESTERDAY,
    LAST_7_DAYS,
    LAST_30_DAYS,
    EARLIER,
}

sealed class DrawerItem {
    data class Section(val section: ConversationSection) : DrawerItem()

    data class Entry(val conversation: Conversation) : DrawerItem()
}
