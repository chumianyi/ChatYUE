package com.chatyue.chat.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.chatyue.chat.ChatMessage
import com.chatyue.chat.ChatMessageAdapter
import com.chatyue.chat.CompatToast
import com.chatyue.chat.MessageAction
import com.chatyue.chat.R
import io.noties.markwon.Markwon

/**
 * 工具页公共能力：标题栏返回、Markdown 渲染、消息列表初始化。
 */
abstract class BaseToolActivity : AppCompatActivity() {

    protected val markwon: Markwon by lazy { Markwon.create(this) }

    protected fun setupBackButton(button: View) {
        button.setOnClickListener { finish() }
    }

    protected fun toast(message: String) {
        CompatToast.show(this, message, Toast.LENGTH_SHORT)
    }

    protected fun longToast(message: String) {
        CompatToast.show(this, message, Toast.LENGTH_LONG)
    }

    protected fun setupMessageList(recyclerView: RecyclerView): ChatMessageAdapter {
        lateinit var adapter: ChatMessageAdapter
        adapter = ChatMessageAdapter(
            markwon = markwon,
            onAction = { action, message ->
                onMessageAction(adapter, action, message)
            },
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        recyclerView.itemAnimator = null
        recyclerView.setHasFixedSize(false)
        return adapter
    }

    /**
     * AI 回答底部操作栏的默认实现：复制到剪贴板、点赞 / 点踩本地切换。
     * 子类如需扩展可覆写。
     */
    protected open fun onMessageAction(
        adapter: ChatMessageAdapter,
        action: MessageAction,
        message: ChatMessage,
    ) {
        when (action) {
            MessageAction.COPY -> copyToClipboard(message.content)
            MessageAction.LIKE -> {
                if (adapter.toggleLike(message.id)) {
                    toast(getString(R.string.action_like_done))
                }
            }
            MessageAction.DISLIKE -> {
                if (adapter.toggleDislike(message.id)) {
                    toast(getString(R.string.action_dislike_done))
                }
            }
        }
    }

    private fun copyToClipboard(text: String) {
        if (text.isEmpty()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
        // Android 13 起系统自带「已复制」提示，避免重复弹出。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            toast(getString(R.string.action_copy_done))
        }
    }

    protected fun scrollListToBottom(
        recyclerView: RecyclerView,
        adapter: ChatMessageAdapter,
    ) {
        if (adapter.itemCount == 0) return
        recyclerView.post { recyclerView.scrollToPosition(adapter.itemCount - 1) }
    }
}
