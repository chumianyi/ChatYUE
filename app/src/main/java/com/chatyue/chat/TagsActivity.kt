package com.chatyue.chat

import android.app.AlertDialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.chatyue.chat.databinding.ActivityTagsBinding
import com.chatyue.chat.databinding.DialogTagEditBinding
import com.chatyue.chat.databinding.ItemTagBinding

/**
 * 标签管理页：新建 / 编辑 / 删除会话标签，并挑选标签颜色。
 *
 * 标签本身不挂载到具体会话——挂载动作在侧边栏会话长按菜单里完成，
 * 这里只维护「标签字典」。
 */
class TagsActivity : AppCompatActivity() {
    /** 预设调色板：覆盖冷暖与中性色，足够区分不同分类。 */
    private val palette = listOf(
        "#FF4D6BFE", "#FFE0524E", "#FF2DBE7E", "#FFF5A623",
        "#FF9B59B6", "#FF1ABC9C", "#FF607D8B", "#FFE91E63",
    )

    private lateinit var binding: ActivityTagsBinding
    private lateinit var repository: ChatRepository
    private lateinit var adapter: TagAdapter
    private var selectedColor: String = palette.first()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTagsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.backButton.setOnClickListener { finish() }

        repository = LocalChatRepository(this)
        adapter = TagAdapter(
            onEdit = ::showEditDialog,
            onDelete = ::confirmDelete,
        )
        binding.tagList.layoutManager = LinearLayoutManager(this)
        binding.tagList.adapter = adapter
        binding.tagList.itemAnimator = null
        binding.addButton.setOnClickListener { showEditDialog(null) }
    }

    override fun onResume() {
        super.onResume()
        loadTags()
    }

    private fun loadTags() {
        val tags = repository.getTags()
        adapter.submit(tags)
        binding.emptyText.visibility = if (tags.isEmpty()) View.VISIBLE else View.GONE
        binding.tagList.visibility = if (tags.isEmpty()) View.GONE else View.VISIBLE
    }

    /** 新建（tag=null）或编辑（传入已有标签）共用同一个对话框。 */
    private fun showEditDialog(tag: Tag?) {
        selectedColor = tag?.colorHex ?: palette.first()
        val dialogBinding = DialogTagEditBinding.inflate(layoutInflater)
        val nameInput: EditText = dialogBinding.nameInput
        if (tag != null) nameInput.setText(tag.name)

        // 动态生成调色板色块，选中态用描边高亮。
        val colorRow = dialogBinding.colorRow
        val swatches = mutableListOf<ColorSwatch>()
        palette.forEach { color ->
            val swatch = ColorSwatch(this).apply {
                setColor(color)
                setOnClickListener {
                    selectedColor = color
                    swatches.forEach { it.setSelectedState(color == selectedColor) }
                }
                setSelectedState(color == selectedColor)
            }
            swatches.add(swatch)
            colorRow.addView(swatch)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (tag == null) R.string.tags_add else R.string.tags_edit)
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            // 点击确定时再校验名称，避免空名写入。
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString().trim()
                if (name.isEmpty()) {
                    nameInput.error = getString(R.string.tags_name_required)
                    return@setOnClickListener
                }
                if (tag == null) {
                    repository.addTag(name, selectedColor)
                } else {
                    repository.updateTag(tag.copy(name = name, colorHex = selectedColor))
                }
                loadTags()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun confirmDelete(tag: Tag) {
        AlertDialog.Builder(this)
            .setTitle(R.string.tags_delete_title)
            .setMessage(getString(R.string.tags_delete_confirm, tag.name))
            .setPositiveButton(R.string.confirm_delete_positive) { _, _ ->
                repository.deleteTag(tag.id)
                loadTags()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 颜色选择小方块：圆形色点 + 选中描边。 */
    private class ColorSwatch(context: Context) : View(context) {
        private val drawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setSize(40, 40)
        }

        init {
            background = drawable
            val pad = (40 * 0.18).toInt()
            setPadding(pad, pad, pad, pad)
            val lp = ViewGroup.MarginLayoutParams(40, 40).apply {
                marginEnd = 14
            }
            layoutParams = lp
        }

        fun setColor(color: String) {
            drawable.setColor(android.graphics.Color.parseColor(color))
        }

        fun setSelectedState(selected: Boolean) {
            drawable.setStroke(
                if (selected) 4 else 0,
                ContextCompat.getColor(context, R.color.ds_surface),
            )
            // 选中时外圈留白描边，未选中时贴近底色。
            val pad = if (selected) (40 * 0.18).toInt() else 0
            setPadding(pad, pad, pad, pad)
        }
    }

    /** 标签列表适配器：无差异刷新，条数通常不大。 */
    private class TagAdapter(
        private val onEdit: (Tag) -> Unit,
        private val onDelete: (Tag) -> Unit,
    ) : RecyclerView.Adapter<TagAdapter.Holder>() {

        private val items = mutableListOf<Tag>()

        fun submit(list: List<Tag>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemTagBinding.inflate(
                LayoutInflater.from(parent.context), parent, false,
            )
            return Holder(binding)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position], onEdit, onDelete)
        }

        override fun getItemCount(): Int = items.size

        private class Holder(
            private val binding: ItemTagBinding,
        ) : RecyclerView.ViewHolder(binding.root) {

            fun bind(
                tag: Tag,
                onEdit: (Tag) -> Unit,
                onDelete: (Tag) -> Unit,
            ) {
                binding.tagName.text = tag.name
                binding.colorDot.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor(tag.colorHex),
                    )
                binding.editButton.setOnClickListener { onEdit(tag) }
                binding.deleteButton.setOnClickListener { onDelete(tag) }
            }
        }
    }
}
