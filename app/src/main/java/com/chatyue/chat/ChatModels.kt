package com.chatyue.chat

/** 会话的记忆模式（2.0 新建会话时由用户选择）。 */
enum class ConversationMemoryMode {
    /** 全局对话：接入全域记忆系统，与所有历史会话数据互通共享。 */
    GLOBAL,

    /** 单独对话：当前会话独立隔离运行，不参与全局记忆。 */
    ISOLATED,
    ;

    companion object {
        fun fromName(value: String?): ConversationMemoryMode =
            entries.firstOrNull { it.name == value } ?: GLOBAL
    }
}

data class Conversation(
    val id: Long,
    val title: String,
    val updatedAt: Long,
    val memoryMode: ConversationMemoryMode = ConversationMemoryMode.GLOBAL,
    val pinned: Boolean = false,
    /** 该会话挂载的标签 id 列表，用于在侧边栏按标签筛选与归类。 */
    val tags: List<Long> = emptyList(),
)

/**
 * 会话标签：用户自建的彩色分类，可挂在多个会话上。
 *
 * [colorHex] 形如 `#FF4D6BFE`，既用于列表里的色点，也用于筛选 chip 的描边。
 */
data class Tag(
    val id: Long,
    val name: String,
    val colorHex: String,
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
    /** 被引用消息的 id；null 表示这条消息没有引用任何消息。 */
    val repliedToId: Long? = null,
    /** 被引用消息的角色（用户或助手），用于气泡内引用块的小标题。 */
    val repliedRole: MessageRole? = null,
    /** 被引用内容的摘要预览，长文本会被截断后存入。 */
    val repliedPreview: String? = null,
    /** 是否被用户收藏（星标），收藏的消息可在「收藏」列表里集中查看。 */
    val starred: Boolean = false,
)

/**
 * 跨会话消息搜索的一条命中结果。
 *
 * 只搜索用户与助手的正文，思考过程与压缩摘要不计入。
 */
data class MessageHit(
    val messageId: Long,
    val conversationId: Long,
    val conversationTitle: String,
    val role: MessageRole,
    val content: String,
    val createdAt: Long,
)

/** 收藏列表里的一条消息，附带它所属会话的标题。 */
data class StarredMessage(
    val message: ChatMessage,
    val conversationTitle: String,
)

/** 侧边栏会话按时间由近到远分组。 */
enum class ConversationSection {
    /** 置顶会话，固定排在最前。 */
    PINNED,

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
