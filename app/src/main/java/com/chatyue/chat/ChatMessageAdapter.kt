package com.chatyue.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import io.noties.markwon.Markwon
import com.chatyue.chat.databinding.ItemMessageAssistantBinding
import com.chatyue.chat.databinding.ItemMessageSummaryBinding
import com.chatyue.chat.databinding.ItemMessageThinkingBinding
import com.chatyue.chat.databinding.ItemMessageUserBinding

class ChatMessageAdapter(
    private val markwon: Markwon,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val messages = mutableListOf<ChatMessage>()
    private val collapsedThinkingIds = mutableSetOf<Long>()

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
                holder.bind(message, showLogo = !followsThinking)
            }
            is ThinkingMessageViewHolder -> {
                holder.bind(message, collapsedThinkingIds.contains(message.id))
            }
            is SummaryMessageViewHolder -> holder.bind(message)
        }
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

    private fun toggleThinking(messageId: Long) {
        if (!collapsedThinkingIds.add(messageId)) {
            collapsedThinkingIds.remove(messageId)
        }
        val position = messages.indexOfFirst { it.id == messageId }
        if (position >= 0) notifyItemChanged(position)
    }

    private class UserMessageViewHolder(
        private val binding: ItemMessageUserBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: ChatMessage) {
            binding.messageText.text = message.content
        }
    }

    private class AssistantMessageViewHolder(
        private val binding: ItemMessageAssistantBinding,
        private val markwon: Markwon,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: ChatMessage, showLogo: Boolean) {
            binding.assistantLogo.visibility = if (showLogo) View.VISIBLE else View.GONE
            markwon.setMarkdown(binding.messageText, message.content)
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
        private const val VIEW_TYPE_USER = 1
        private const val VIEW_TYPE_ASSISTANT = 2
        private const val VIEW_TYPE_THINKING = 3
        private const val VIEW_TYPE_SUMMARY = 4
    }
}
