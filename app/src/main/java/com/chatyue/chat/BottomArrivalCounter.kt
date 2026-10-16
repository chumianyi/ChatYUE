package com.chatyue.chat

/**
 * 「回到底部」未读计数（2.0 新增）。
 *
 * 聊天界面里，用户向上翻看历史时不应被打断：新到达的消息只累加计数，
 * 由悬浮按钮提示条数；用户回到底部（或切到新会话）后清零。
 *
 * 逻辑与视图解耦，便于单元测试。
 */
class BottomArrivalCounter {
    private var lastTailId: Long? = null

    /** 离开底部期间累计的新消息条数。 */
    var unreadCount: Int = 0
        private set

    /**
     * 记录一次列表渲染。
     *
     * @param tailId 列表尾部消息 id；列表为空时传 null。
     * @param atBottom 用户当前是否停在底部。
     * @return true 表示本次有新消息到达且用户不在底部（计数已 +1）。
     */
    fun onRender(tailId: Long?, atBottom: Boolean): Boolean {
        if (tailId == null) {
            reset()
            return false
        }
        val arrived = tailId != lastTailId
        lastTailId = tailId
        if (atBottom) {
            // 待在底部：持续贴底，不留计数。
            unreadCount = 0
            return false
        }
        if (arrived) {
            unreadCount += 1
        }
        return arrived
    }

    /** 回到底部：清零计数；尾部 id 保持不变，避免同一条消息被重复计为新到达。 */
    fun clearOnReturnToBottom() {
        unreadCount = 0
    }

    /** 会话切换 / 新对话：连同尾部标记一起复位。 */
    fun reset() {
        lastTailId = null
        unreadCount = 0
    }
}
