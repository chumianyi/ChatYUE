package com.chatyue.chat

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.chatyue.chat.databinding.ActivityMessageSearchBinding
import java.util.concurrent.atomic.AtomicInteger

/**
 * 跨会话消息搜索（2.0 新增）。
 *
 * 输入关键词后，在所有会话的正文中检索（仅用户与助手消息），
 * 点击结果回到主界面并打开对应的会话。
 */
class MessageSearchActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMessageSearchBinding
    private val repository by lazy { LocalChatRepository(this) }
    private lateinit var adapter: MessageHitAdapter

    /** 递增令牌：只有最新一次输入触发的搜索结果才会被采用，丢弃过期结果。 */
    private val searchToken = AtomicInteger(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMessageSearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.backButton.setOnClickListener { finish() }

        adapter = MessageHitAdapter { hit ->
            setResult(
                RESULT_OK,
                Intent().apply {
                    putExtra(EXTRA_OPEN_CONVERSATION, hit.conversationId)
                    putExtra(EXTRA_OPEN_MESSAGE, hit.messageId)
                },
            )
            finish()
        }
        binding.resultList.layoutManager = LinearLayoutManager(this)
        binding.resultList.adapter = adapter

        binding.searchInput.doAfterTextChanged { editable ->
            val query = editable?.toString().orEmpty()
            if (query.isBlank()) {
                showIdle()
            } else {
                runSearch(query)
            }
        }
        showIdle()
    }

    private fun showIdle() {
        searchToken.incrementAndGet()
        adapter.submit(emptyList(), "")
        binding.emptyText.visibility = View.VISIBLE
        binding.emptyText.setText(R.string.message_search_empty)
        binding.resultCountLabel.text = ""
    }

    private fun runSearch(query: String) {
        val token = searchToken.incrementAndGet()
        // 本地数据库检索，数据量可控，直接查询即可。
        val hits = repository.searchMessages(query, SEARCH_LIMIT)
        // 用户已输入新关键词，丢弃这次过期结果。
        if (token != searchToken.get()) return
        if (hits.isEmpty()) {
            adapter.submit(emptyList(), query)
            binding.emptyText.visibility = View.VISIBLE
            binding.emptyText.setText(R.string.message_search_no_result)
            binding.resultCountLabel.text = ""
        } else {
            adapter.submit(hits, query)
            binding.emptyText.visibility = View.GONE
            binding.resultCountLabel.text =
                getString(R.string.message_search_result_count, hits.size)
        }
    }

    override fun onDestroy() {
        repository.close()
        super.onDestroy()
    }

    companion object {
        /** 打开会话时回传给主页的 Extra 键。 */
        const val EXTRA_OPEN_CONVERSATION = "com.chatyue.chat.OPEN_CONVERSATION"

        /** 回传命中的具体消息，主页打开会话后定位并高亮该消息。 */
        const val EXTRA_OPEN_MESSAGE = "com.chatyue.chat.OPEN_MESSAGE"

        private const val SEARCH_LIMIT = 200
    }
}
