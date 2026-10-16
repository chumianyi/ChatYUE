package com.chatyue.chat

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会话导出：把聊天记录渲染成 Markdown / 纯文本，用于分享与存档。
 *
 * 只导出用户消息、助手回答与上下文压缩摘要；流式思考过程属于中间态，不入导出稿。
 */
object ConversationExporter {
    private const val TIME_PATTERN = "yyyy-MM-dd HH:mm"

    /** 导出稿中允许出现的角色名前。 */
    private const val SPEAKER_USER = "我"
    private const val SPEAKER_ASSISTANT = "ChatYUE"
    private const val SPEAKER_SYSTEM = "上下文摘要"

    private val timeFormat: SimpleDateFormat
        get() = SimpleDateFormat(TIME_PATTERN, Locale.getDefault())

    /** 生成 Markdown 导出稿。 */
    fun toMarkdown(conversation: Conversation, messages: List<ChatMessage>): String {
        val exported = messages.filter { it.role != MessageRole.THINKING_ACTIVE }
        val body = exported.joinToString(separator = "\n\n") { message ->
            val speaker = speakerOf(message.role)
            val quoted = quoteLine(message)
            val time = timeFormat.format(Date(message.createdAt))
            buildString {
                append("**$speaker** · $time")
                if (quoted != null) {
                    append("\n\n")
                    append(quoted)
                }
                append("\n\n")
                append(message.content.trim())
            }
        }
        return buildString {
            append("# ")
            append(conversation.title)
            append("\n\n")
            append(
                "> 导出自 ChatYUE · " +
                    timeFormat.format(Date()) +
                    " · 共 ${exported.size} 条消息",
            )
            append("\n\n---\n\n")
            append(body.ifBlank { "（暂无消息）" })
            append("\n")
        }
    }

    /** 生成纯文本导出稿（去掉 Markdown 记号，适合粘贴到纯文本环境）。 */
    fun toPlainText(conversation: Conversation, messages: List<ChatMessage>): String {
        val exported = messages.filter { it.role != MessageRole.THINKING_ACTIVE }
        val body = exported.joinToString(separator = "\n\n") { message ->
            val speaker = speakerOf(message.role)
            val time = timeFormat.format(Date(message.createdAt))
            val quoted = message.repliedPreview?.let { preview ->
                val quotedSpeaker = message.repliedRole?.let { speakerOf(it) } ?: ""
                "\n  └ 引用$quotedSpeaker：$preview"
            } ?: ""
            "$speaker · $time$quoted\n${message.content.trim()}"
        }
        return buildString {
            append(conversation.title)
            append("\n")
            append(
                "导出自 ChatYUE · " +
                    timeFormat.format(Date()) +
                    " · 共 ${exported.size} 条消息",
            )
            append("\n\n")
            append(body.ifBlank { "（暂无消息）" })
            append("\n")
        }
    }

    /**
     * 生成导出文件名：过滤文件系统非法字符，超长截断。
     *
     * @param extension 不含点号的扩展名，如 "md" / "txt"。
     */
    fun fileNameFor(conversation: Conversation, extension: String): String {
        val sanitized = conversation.title
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_")
            .trim()
            .take(FILE_NAME_MAX_TITLE_LENGTH)
            .ifBlank { DEFAULT_TITLE }
        return "$sanitized.$extension"
    }

    /** 被引用消息在导出稿里的脚注行；无引用返回 null。 */
    private fun quoteLine(message: ChatMessage): String? {
        if (message.repliedToId == null || message.repliedPreview.isNullOrEmpty()) {
            return null
        }
        val speaker = message.repliedRole?.let { speakerOf(it) } ?: return null
        return "> 引用$speaker：${message.repliedPreview}"
    }

    private fun speakerOf(role: MessageRole): String = when (role) {
        MessageRole.USER -> SPEAKER_USER
        MessageRole.ASSISTANT -> SPEAKER_ASSISTANT
        MessageRole.SYSTEM -> SPEAKER_SYSTEM
        MessageRole.THINKING_ACTIVE, MessageRole.THINKING_FINISHED -> SPEAKER_SYSTEM
    }

    private const val FILE_NAME_MAX_TITLE_LENGTH = 32
    private const val DEFAULT_TITLE = "会话导出"
}
