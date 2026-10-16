package com.chatyue.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.chatyue.chat.databinding.ItemPromptBinding

/**
 * 快捷指令库列表适配器。
 *
 * 点击把指令正文回传给主页填入输入框，长按（以条目视图为锚点）弹编辑 / 删除菜单。
 */
class PromptAdapter(
    private val onClick: (Prompt) -> Unit,
    private val onLongClick: (Prompt, View) -> Unit,
) : RecyclerView.Adapter<PromptAdapter.PromptViewHolder>() {
    private val items = mutableListOf<Prompt>()

    fun submit(newItems: List<Prompt>) {
        val diff = DiffUtil.calculateDiff(PromptDiff(items, newItems))
        items.clear()
        items.addAll(newItems)
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PromptViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return PromptViewHolder(ItemPromptBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: PromptViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class PromptViewHolder(
        private val binding: ItemPromptBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(prompt: Prompt) {
            binding.promptTitle.text = prompt.title
            binding.promptPreview.text = PromptFormatter.preview(prompt.content)
            binding.promptUsage.text = binding.root.context.getString(
                R.string.prompt_usage_count,
                prompt.usageCount,
            )
            binding.root.setOnClickListener { onClick(prompt) }
            binding.root.setOnLongClickListener { view ->
                onLongClick(prompt, view)
                true
            }
        }
    }

    private class PromptDiff(
        private val oldList: List<Prompt>,
        private val newList: List<Prompt>,
    ) : DiffUtil.Callback() {
        override fun getOldListSize(): Int = oldList.size

        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
            oldList[oldItemPosition].id == newList[newItemPosition].id

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
            oldList[oldItemPosition] == newList[newItemPosition]
    }
}
