package com.chatyue.chat

import android.content.Context

/**
 * 每会话草稿箱。
 *
 * 切换会话时自动保存未发送的输入，回到该会话再原样恢复；
 * 还没落库的新会话用固定键，保证开屏写的草稿也不会丢。
 */
class DraftStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 保存草稿；空草稿直接清键，避免磁盘里堆无效值。 */
    fun save(conversationId: Long?, draft: String) {
        val key = keyOf(conversationId)
        prefs.edit().apply {
            if (draft.isEmpty()) remove(key) else putString(key, draft)
        }.apply()
    }

    fun load(conversationId: Long?): String =
        prefs.getString(keyOf(conversationId), "").orEmpty()

    fun clear(conversationId: Long?) {
        prefs.edit().remove(keyOf(conversationId)).apply()
    }

    /** 清空全部草稿（清空历史数据时调用）。 */
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    private fun keyOf(conversationId: Long?): String =
        if (conversationId == null) KEY_NEW_CONVERSATION else "$KEY_PREFIX$conversationId"

    companion object {
        private const val PREFS_NAME = "chatyue_drafts"
        private const val KEY_PREFIX = "draft_"
        private const val KEY_NEW_CONVERSATION = "draft_new"
    }
}
