package com.chatyue.chat

/**
 * 单条消息分享文本（2.0 新增）。
 *
 * 长按消息「分享」与多选「分享」共用：给对方一段能直接读懂的对话片段，
 * 附上角色前缀与来源署名，不暴露思考过程与引用元数据。
 *
 * 纯函数实现，便于单元测试。
 */
object MessageShareFormatter {

    /** 分享文本的来源署名。 */
    private const val SIGNATURE = "—— 来自 ChatYUE"

    /** 用户消息的角色前缀。 */
    private const val ROLE_LABEL_USER = "我"

    /** 助手消息的角色前缀。 */
    private const val ROLE_LABEL_ASSISTANT = "AI"

    /**
     * 构造单条消息的分享文本。
     *
     * 思考过程（流式中 / 已完成）不参与分享；系统摘要则标明是压缩摘要。
     */
    fun format(message: ChatMessage): String {
        val body = message.content.trim()
        val label = when (message.role) {
            MessageRole.USER -> ROLE_LABEL_USER
            MessageRole.ASSISTANT -> null
            MessageRole.SYSTEM -> null
            MessageRole.THINKING_ACTIVE, MessageRole.THINKING_FINISHED -> return SIGNATURE
        }
        val header = if (label != null) "$label：$body" else body
        return "$header\n\n$SIGNATURE"
    }

    /**
     * 构造多条消息的分享文本：按给定顺序拼接为一段对话，署名只在末尾出现一次。
     *
     * 思考过程与压缩摘要不参与分享。
     */
    fun formatAll(messages: List<ChatMessage>): String {
        val bodies = messages
            .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
            .map { message ->
                val body = message.content.trim()
                if (message.role == MessageRole.USER) {
                    "$ROLE_LABEL_USER：$body"
                } else {
                    body
                }
            }
        if (bodies.isEmpty()) return ""
        return bodies.joinToString(separator = "\n\n") + "\n\n$SIGNATURE"
    }
}
