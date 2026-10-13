package com.deepseekold.chat

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.deepseekold.chat.databinding.ItemConversationBinding
import com.deepseekold.chat.databinding.ItemSectionHeaderBinding

class ConversationAdapter(
    private val onConversationSelected: (Conversation) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<DrawerItem>()
    private var selectedConversationId: Long? = null

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is DrawerItem.Section -> VIEW_TYPE_SECTION
            is DrawerItem.Entry -> VIEW_TYPE_CONVERSATION
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_SECTION -> {
                SectionViewHolder(
                    ItemSectionHeaderBinding.inflate(inflater, parent, false),
                )
            }

            else -> {
                ConversationViewHolder(
                    ItemConversationBinding.inflate(inflater, parent, false),
                    onConversationSelected,
                )
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is DrawerItem.Section -> {
                (holder as SectionViewHolder).bind(item.section)
            }

            is DrawerItem.Entry -> {
                (holder as ConversationViewHolder).bind(
                    item.conversation,
                    item.conversation.id == selectedConversationId,
                )
            }
        }
    }

    override fun getItemCount(): Int = items.size

    fun submitItems(newItems: List<DrawerItem>, selectedId: Long?) {
        items.clear()
        items.addAll(newItems)
        selectedConversationId = selectedId
        notifyDataSetChanged()
    }

    private class SectionViewHolder(
        private val binding: ItemSectionHeaderBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(section: ConversationSection) {
            binding.sectionTitle.setText(
                when (section) {
                    ConversationSection.TODAY -> R.string.section_today
                    ConversationSection.YESTERDAY -> R.string.section_yesterday
                    ConversationSection.LAST_7_DAYS -> R.string.section_last_7_days
                    ConversationSection.LAST_30_DAYS -> R.string.section_last_30_days
                    ConversationSection.EARLIER -> R.string.section_earlier
                },
            )
        }
    }

    private class ConversationViewHolder(
        private val binding: ItemConversationBinding,
        private val onConversationSelected: (Conversation) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var conversation: Conversation? = null

        init {
            binding.root.setOnClickListener {
                conversation?.let(onConversationSelected)
            }
        }

        fun bind(item: Conversation, isSelected: Boolean) {
            conversation = item
            binding.conversationTitle.text = item.title
            binding.conversationTitle.isActivated = isSelected
        }
    }

    companion object {
        private const val VIEW_TYPE_SECTION = 1
        private const val VIEW_TYPE_CONVERSATION = 2
    }
}
