package com.chatyue.chat

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.chatyue.chat.databinding.ItemMessageHitBinding

/**
 * 跨会话消息搜索结果的列表适配器。
 *
 * 每条展示：角色（我 / ChatYUE）、时间、所属会话标题、正文片段。
 */
class MessageHitAdapter(
    private val onClick: (MessageHit) -> Unit,
) : RecyclerView.Adapter<MessageHitAdapter.HitViewHolder>() {
    private val items = mutableListOf<MessageHit>()

    fun submit(newItems: List<MessageHit>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HitViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return HitViewHolder(ItemMessageHitBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: HitViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class HitViewHolder(
        private val binding: ItemMessageHitBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(hit: MessageHit) {
            binding.roleLabel.setText(
                if (hit.role == MessageRole.USER) {
                    R.string.role_user
                } else {
                    R.string.role_assistant
                },
            )
            binding.timeLabel.text = DateUtils.formatDateTime(
                binding.root.context,
                hit.createdAt,
                DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE,
            )
            binding.conversationTitle.text = hit.conversationTitle
            binding.snippetText.text = hit.content
            binding.root.setOnClickListener { onClick(hit) }
        }
    }
}
