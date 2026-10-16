package com.chatyue.chat

import android.graphics.Typeface
import android.text.SpannableString
import android.text.format.DateUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.chatyue.chat.databinding.ItemMessageHitBinding

/**
 * 跨会话消息搜索结果的列表适配器。
 *
 * 每条展示：角色（我 / ChatYUE）、距今时间、所属会话标题、正文片段。
 * 片段由 [SearchSnippetFactory] 以关键词为中心截取，命中词加粗高亮。
 */
class MessageHitAdapter(
    private val onClick: (MessageHit) -> Unit,
) : RecyclerView.Adapter<MessageHitAdapter.HitViewHolder>() {
    private val items = mutableListOf<MessageHit>()

    /** 当前搜索词，用于生成摘要片段与高亮区间。 */
    private var keyword: String = ""

    fun submit(newItems: List<MessageHit>, query: String) {
        items.clear()
        items.addAll(newItems)
        keyword = query.trim()
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
            // 搜索结果看重「多久之前」而不是绝对时刻。
            binding.timeLabel.text = DateUtils.getRelativeTimeSpanString(
                hit.createdAt,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
            )
            binding.conversationTitle.text = hit.conversationTitle
            binding.snippetText.text = buildSnippet(hit)
            binding.root.setOnClickListener { onClick(hit) }
        }

        /**
         * 生成摘要片段：关键词附近截窗 + 命中词加粗高亮。
         *
         * 省略号与片段文本拼接成完整字符串后再平移高亮区间；
         * 区间可能被窗口裁掉一半（搜索词比窗口长），重叠校验后再上 span。
         */
        private fun buildSnippet(hit: MessageHit): CharSequence {
            val snippet = SearchSnippetFactory.extract(hit.content, keyword)
            val leading = if (snippet.hasLeadingEllipsis) "…" else ""
            val trailing = if (snippet.hasTrailingEllipsis) "…" else ""
            val display = leading + snippet.text + trailing
            val spannable = SpannableString(display)
            val highlightColor = ContextCompat.getColor(binding.root.context, R.color.ds_blue)
            val offset = leading.length
            snippet.matchRanges.forEach { range ->
                val start = (range.first + offset).coerceIn(0, display.length)
                val end = (range.last + 1 + offset).coerceIn(0, display.length)
                if (start >= end) return@forEach
                spannable.setSpan(
                    StyleSpan(Typeface.BOLD),
                    start,
                    end,
                    SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                spannable.setSpan(
                    ForegroundColorSpan(highlightColor),
                    start,
                    end,
                    SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            return spannable
        }
    }
}
