package com.chatyue.chat.tools

import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.chatyue.chat.ChatMessageAdapter
import com.chatyue.chat.CompatToast
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
        val adapter = ChatMessageAdapter(markwon)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        recyclerView.itemAnimator = null
        recyclerView.setHasFixedSize(false)
        return adapter
    }

    protected fun scrollListToBottom(
        recyclerView: RecyclerView,
        adapter: ChatMessageAdapter,
    ) {
        if (adapter.itemCount == 0) return
        recyclerView.post { recyclerView.scrollToPosition(adapter.itemCount - 1) }
    }
}
