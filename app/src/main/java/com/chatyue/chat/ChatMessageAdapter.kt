package com.chatyue.chat

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import io.noties.markwon.Markwon
import com.chatyue.chat.databinding.ItemMessageAssistantBinding
import com.chatyue.chat.databinding.ItemMessageSummaryBinding
import com.chatyue.chat.databinding.ItemMessageThinkingBinding
import com.chatyue.chat.databinding.ItemMessageUserBinding

/** 消息底部的操作按钮。 */
enum class MessageAction {
    COPY,
    LIKE,
    DISLIKE,
}

class ChatMessageAdapter(
    private val markwon: Markwon,
    private val onAction: (MessageAction, ChatMessage) -> Unit,
    private val onLongPress: (ChatMessage, View) -> Unit = { _, _ -> },
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val messages = mutableListOf<ChatMessage>()
    private val collapsedThinkingIds = mutableSetOf<Long>()

    /** 当前正在流式输出的回答 id，它的操作栏暂时不显示。 */
    private var streamingMessageId: Long? = null

    /** 点赞 / 点踩的本地状态（互斥）。 */
    private val likedIds = mutableSetOf<Long>()
    private val dislikedIds = mutableSetOf<Long>()

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = messages[position].id

    override fun getItemViewType(position: Int): Int {
        return when (messages[position].role) {
            MessageRole.USER -> VIEW_TYPE_USER
            MessageRole.ASSISTANT -> VIEW_TYPE_ASSISTANT
            MessageRole.SYSTEM -> VIEW_TYPE_SUMMARY
            MessageRole.THINKING_ACTIVE,
            MessageRole.THINKING_FINISHED -> VIEW_TYPE_THINKING
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_USER -> {
                UserMessageViewHolder(
                    ItemMessageUserBinding.inflate(inflater, parent, false),
                    onLongPress,
                )
            }

            VIEW_TYPE_THINKING -> {
                ThinkingMessageViewHolder(
                    ItemMessageThinkingBinding.inflate(inflater, parent, false),
                    ::toggleThinking,
                )
            }

            VIEW_TYPE_SUMMARY -> {
                SummaryMessageViewHolder(
                    ItemMessageSummaryBinding.inflate(inflater, parent, false),
                    markwon,
                )
            }

            else -> {
                AssistantMessageViewHolder(
                    ItemMessageAssistantBinding.inflate(inflater, parent, false),
                    markwon,
                    onAction,
                    onLongPress,
                )
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = messages[position]
        when (holder) {
            is UserMessageViewHolder -> holder.bind(message)
            is AssistantMessageViewHolder -> {
                val followsThinking = position > 0 &&
                    (messages[position - 1].role == MessageRole.THINKING_FINISHED ||
                        messages[position - 1].role == MessageRole.THINKING_ACTIVE)
                holder.bind(
                    message = message,
                    showLogo = !followsThinking,
                    showActions = message.id != streamingMessageId,
                    liked = likedIds.contains(message.id),
                    disliked = dislikedIds.contains(message.id),
                )
            }
            is ThinkingMessageViewHolder -> {
                holder.bind(message, collapsedThinkingIds.contains(message.id))
            }
            is SummaryMessageViewHolder -> holder.bind(message)
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>,
    ) {
        // 只改了点赞 / 点踩状态时，避免重新解析 Markdown，防止长回答卡顿。
        if (payloads.isNotEmpty() && holder is AssistantMessageViewHolder) {
            val message = messages[position]
            holder.bindActions(
                showActions = message.id != streamingMessageId,
                liked = likedIds.contains(message.id),
                disliked = dislikedIds.contains(message.id),
            )
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun getItemCount(): Int = messages.size

    fun submitMessages(newMessages: List<ChatMessage>) {
        val oldMessages = messages.toList()
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldMessages.size

            override fun getNewListSize(): Int = newMessages.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return oldMessages[oldItemPosition].id == newMessages[newItemPosition].id
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return oldMessages[oldItemPosition] == newMessages[newItemPosition]
            }
        })
        messages.clear()
        messages.addAll(newMessages)
        collapsedThinkingIds.retainAll(
            newMessages
                .filter {
                    it.role == MessageRole.THINKING_ACTIVE ||
                        it.role == MessageRole.THINKING_FINISHED
                }
                .map { it.id }
                .toSet(),
        )
        diff.dispatchUpdatesTo(this)
    }

    /** 记录正在流式输出的回答 id，流式期间它的操作栏不显示。 */
    fun setStreamingMessageId(messageId: Long?) {
        if (streamingMessageId == messageId) return
        streamingMessageId = messageId
        // 只有 AI 回答带操作栏，逐项刷新即可，不必整表重绑。
        messages.forEachIndexed { index, message ->
            if (message.role == MessageRole.ASSISTANT) {
                notifyItemChanged(index, PAYLOAD_ACTIONS)
            }
        }
    }

    /** 切换点赞，与点踩互斥；返回切换后的状态。 */
    fun toggleLike(messageId: Long): Boolean {
        val liked = if (likedIds.add(messageId)) {
            dislikedIds.remove(messageId)
            true
        } else {
            likedIds.remove(messageId)
            false
        }
        notifyMessage(messageId)
        return liked
    }

    /** 切换点踩，与点赞互斥；返回切换后的状态。 */
    fun toggleDislike(messageId: Long): Boolean {
        val disliked = if (dislikedIds.add(messageId)) {
            likedIds.remove(messageId)
            true
        } else {
            dislikedIds.remove(messageId)
            false
        }
        notifyMessage(messageId)
        return disliked
    }

    private fun notifyMessage(messageId: Long) {
        val position = messages.indexOfFirst { it.id == messageId }
        if (position >= 0) notifyItemChanged(position, PAYLOAD_ACTIONS)
    }

    private fun toggleThinking(messageId: Long) {
        if (!collapsedThinkingIds.add(messageId)) {
            collapsedThinkingIds.remove(messageId)
        }
        val position = messages.indexOfFirst { it.id == messageId }
        if (position >= 0) notifyItemChanged(position)
    }

    private class UserMessageViewHolder(
        private val binding: ItemMessageUserBinding,
        private val onLongPress: (ChatMessage, View) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: ChatMessage) {
            binding.messageText.text = message.content
            binding.root.setOnLongClickListener {
                onLongPress(message, binding.messageText)
                true
            }
        }
    }

    private class AssistantMessageViewHolder(
        private val binding: ItemMessageAssistantBinding,
        private val markwon: Markwon,
        private val onAction: (MessageAction, ChatMessage) -> Unit,
        private val onLongPress: (ChatMessage, View) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var current: ChatMessage? = null

        /** 复制成功后把图标恢复成复制。 */
        private val resetCopyIcon = Runnable {
            binding.copyButton.setImageResource(R.drawable.ic_action_copy)
        }

        init {
            binding.root.setOnLongClickListener {
                val message = current ?: return@setOnLongClickListener false
                onLongPress(message, binding.messageActions)
                true
            }
            binding.copyButton.setOnClickListener {
                val message = current ?: return@setOnClickListener
                onAction(MessageAction.COPY, message)
                // 复制完成后图标变成勾，稍后自动恢复。
                binding.copyButton.setImageResource(R.drawable.ic_action_check)
                binding.copyButton.removeCallbacks(resetCopyIcon)
                binding.copyButton.postDelayed(resetCopyIcon, COPY_FEEDBACK_MILLIS)
            }
            binding.likeButton.setOnClickListener {
                current?.let { onAction(MessageAction.LIKE, it) }
            }
            binding.dislikeButton.setOnClickListener {
                current?.let { onAction(MessageAction.DISLIKE, it) }
            }
        }

        fun bind(
            message: ChatMessage,
            showLogo: Boolean,
            showActions: Boolean,
            liked: Boolean,
            disliked: Boolean,
        ) {
            current = message
            binding.assistantLogo.visibility = if (showLogo) View.VISIBLE else View.GONE
            markwon.setMarkdown(binding.messageText, message.content)

            // 复用视图时清掉上一次的复制反馈。
            binding.copyButton.removeCallbacks(resetCopyIcon)
            binding.copyButton.setImageResource(R.drawable.ic_action_copy)

            bindActions(showActions, liked, disliked)
        }

        /** 只更新操作栏（点赞 / 点踩状态变化时调用，不重新渲染正文）。 */
        fun bindActions(
            showActions: Boolean,
            liked: Boolean,
            disliked: Boolean,
        ) {
            binding.messageActions.visibility = if (showActions) View.VISIBLE else View.GONE
            tintAction(binding.likeButton, liked)
            tintAction(binding.dislikeButton, disliked)
            binding.likeButton.contentDescription = binding.root.context.getString(
                if (liked) R.string.action_like_done else R.string.action_like,
            )
        }

        private fun tintAction(
            button: androidx.appcompat.widget.AppCompatImageButton,
            active: Boolean,
        ) {
            val color = ContextCompat.getColor(
                binding.root.context,
                if (active) R.color.ds_blue else R.color.ds_text_secondary,
            )
            button.imageTintList = ColorStateList.valueOf(color)
        }
    }

    private class SummaryMessageViewHolder(
        private val binding: ItemMessageSummaryBinding,
        private val markwon: Markwon,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: ChatMessage) {
            markwon.setMarkdown(binding.summaryText, message.content)
        }
    }

    private class ThinkingMessageViewHolder(
        private val binding: ItemMessageThinkingBinding,
        private val onToggle: (Long) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var messageId: Long = 0L

        init {
            binding.thinkingHeader.setOnClickListener {
                onToggle(messageId)
            }
            binding.thinkingToggle.setOnClickListener {
                onToggle(messageId)
            }
        }

        fun bind(message: ChatMessage, isCollapsed: Boolean) {
            messageId = message.id
            val isActive = message.role == MessageRole.THINKING_ACTIVE
            binding.thinkingTitle.setText(
                if (isActive) {
                    R.string.thinking_in_progress
                } else {
                    R.string.thinking_finished
                },
            )
            binding.thinkingText.text = message.content
            binding.thinkingBody.visibility =
                if (isCollapsed) View.GONE else View.VISIBLE
            binding.thinkingToggle.setImageResource(
                if (isCollapsed) {
                    R.drawable.ic_chevron_down
                } else {
                    R.drawable.ic_chevron_up
                },
            )
            binding.thinkingToggle.contentDescription = binding.root.context.getString(
                if (isCollapsed) {
                    R.string.expand_thinking
                } else {
                    R.string.collapse_thinking
                },
            )
        }
    }

    companion object {
        /** 复制成功后打勾图标的停留时长。 */
        private const val COPY_FEEDBACK_MILLIS = 1500L

        /** 只刷新操作栏的局部更新标记。 */
        private val PAYLOAD_ACTIONS = Any()

        private const val VIEW_TYPE_USER = 1
        private const val VIEW_TYPE_ASSISTANT = 2
        private const val VIEW_TYPE_THINKING = 3
        private const val VIEW_TYPE_SUMMARY = 4
    }
}
