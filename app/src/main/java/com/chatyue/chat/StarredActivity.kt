package com.chatyue.chat

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.chatyue.chat.databinding.ActivityStarredBinding
import com.chatyue.chat.databinding.ItemStarredMessageBinding

/**
 * 收藏列表：集中查看所有被星标的消息，点击跳回对应会话并定位到该消息，
 * 也可在此一键取消收藏。数据来自 [ChatRepository.getStarredMessages]。
 */
class StarredActivity : AppCompatActivity() {
    private lateinit var binding: ActivityStarredBinding
    private lateinit var repository: ChatRepository
    private lateinit var adapter: StarredAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStarredBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.backButton.setOnClickListener { finish() }

        repository = LocalChatRepository(this)
        adapter = StarredAdapter(
            onOpen = ::openStarred,
            onUnstar = ::unstar,
        )
        binding.starredList.layoutManager = LinearLayoutManager(this)
        binding.starredList.adapter = adapter
        binding.starredList.itemAnimator = null
    }

    override fun onResume() {
        super.onResume()
        loadStarred()
    }

    private fun loadStarred() {
        val items = repository.getStarredMessages()
        adapter.submit(items)
        binding.emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.starredList.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun openStarred(item: StarredMessage) {
        // 复用主页的消息跳转定位协议：回传会话 id 与消息 id。
        val intent = Intent().apply {
            putExtra(MessageSearchActivity.EXTRA_OPEN_CONVERSATION, item.message.conversationId)
            putExtra(MessageSearchActivity.EXTRA_OPEN_MESSAGE, item.message.id)
        }
        setResult(RESULT_OK, intent)
        finish()
    }

    private fun unstar(item: StarredMessage) {
        repository.setMessageStarred(item.message.id, starred = false)
        loadStarred()
    }

    /** 收藏条目适配器：无差异刷新，条数通常不大。 */
    private class StarredAdapter(
        private val onOpen: (StarredMessage) -> Unit,
        private val onUnstar: (StarredMessage) -> Unit,
    ) : RecyclerView.Adapter<StarredAdapter.Holder>() {

        private val items = mutableListOf<StarredMessage>()

        fun submit(list: List<StarredMessage>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemStarredMessageBinding.inflate(
                LayoutInflater.from(parent.context), parent, false,
            )
            return Holder(binding)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position], onOpen, onUnstar)
        }

        override fun getItemCount(): Int = items.size

        private class Holder(
            private val binding: ItemStarredMessageBinding,
        ) : RecyclerView.ViewHolder(binding.root) {

            fun bind(
                item: StarredMessage,
                onOpen: (StarredMessage) -> Unit,
                onUnstar: (StarredMessage) -> Unit,
            ) {
                val message = item.message
                binding.conversationTitle.text =
                    ConversationFormatter.titleFrom(item.conversationTitle)
                binding.timeLabel.text =
                    MessageTimeFormatter.format(message.createdAt, System.currentTimeMillis())
                binding.contentText.text = message.content.trim().ifEmpty {
                    binding.root.context.getString(R.string.starred_empty_content)
                }
                binding.roleLabel.setText(
                    if (message.role == MessageRole.USER) R.string.role_user else R.string.role_assistant,
                )
                binding.root.setOnClickListener { onOpen(item) }
                binding.openButton.setOnClickListener { onOpen(item) }
                binding.unstarButton.setOnClickListener { onUnstar(item) }
            }
        }
    }
}
