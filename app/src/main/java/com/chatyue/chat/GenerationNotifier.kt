package com.chatyue.chat

/**
 * 「生成完成」通知栏提醒的文案构造（2.0 新增）。
 *
 * 用户切到后台等长回答时，主进程把生成结果推一条通知，点通知回到应用。
 * 标题带会话名、正文是回复开头的一小段预览，这里只负责纯文本加工，
 * 通知的组装与权限处理在 [GenerationNotifications]。
 */
object GenerationNotifier {

    /** 通知渠道 ID（安卓 8.0+ 的通知渠道标识）。 */
    const val CHANNEL_ID = "chatyue_generation_done"

    /** 预览正文的默认截断长度。 */
    const val DEFAULT_PREVIEW_LENGTH = 60

    /** 通知的唯一 ID：同一时刻只会有一条生成提醒，新的顶掉旧的。 */
    const val NOTIFICATION_ID = 2001

    /** 回复为空时的占位预览。 */
    private const val EMPTY_PREVIEW = "（回复内容为空）"

    /**
     * 通知标题：会话名 + 「回复完成」。
     *
     * 会话标题同样走 [ConversationFormatter.titleFrom] 的清洗逻辑，
     * 保证侧栏里看到的标题和通知里一致；清洗后为空时退回「新对话」。
     */
    fun title(conversationTitle: String): String {
        val cleaned = ConversationFormatter.titleFrom(conversationTitle).ifBlank { "新对话" }
        return "$cleaned · 回复完成"
    }

    /**
     * 预览正文：把回复开头压到 [maxLength] 以内，超长补省略号。
     *
     * 空白符号压成单个空格，避免通知栏里出现大段空行。
     */
    fun preview(content: String, maxLength: Int = DEFAULT_PREVIEW_LENGTH): String {
        val flattened = content
            .replace(Regex("\\s+"), " ")
            .trim()
        if (flattened.isEmpty()) return EMPTY_PREVIEW
        if (flattened.length <= maxLength) return flattened
        return flattened.take(maxLength).trimEnd() + "…"
    }
}
