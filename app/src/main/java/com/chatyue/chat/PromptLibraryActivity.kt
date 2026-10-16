package com.chatyue.chat

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.core.widget.doAfterTextChanged
import com.chatyue.chat.databinding.ActivityPromptLibraryBinding
import java.util.concurrent.Executors

/**
 * 快捷指令库（2.0 新增）。
 *
 * 管理可复用的提示词模板：点击任意指令即回传主页填入输入框；
 * 右上角新增，长按可编辑或删除。首次进入会自动注入一批预置指令。
 */
class PromptLibraryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPromptLibraryBinding
    private val repository by lazy { LocalPromptRepository(this) }
    private lateinit var adapter: PromptAdapter

    /** 全量指令缓存：搜索只做本地过滤，不重复读库。 */
    private var allPrompts: List<Prompt> = emptyList()

    private val ioExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "prompt-io").apply { isDaemon = true }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPromptLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.backButton.setOnClickListener { finish() }

        adapter = PromptAdapter(
            onClick = ::returnPrompt,
            onLongClick = ::showPromptActions,
        )
        binding.promptList.layoutManager = LinearLayoutManager(this)
        binding.promptList.adapter = adapter

        binding.addPromptButton.setOnClickListener { showEditDialog(null) }

        binding.promptSearchInput.doAfterTextChanged { editable ->
            applyFilter(editable?.toString().orEmpty())
        }

        loadPrompts(seedPresets = true)
    }

    /** 读取指令列表；首次为空时先注入预置指令再重新读取。 */
    private fun loadPrompts(seedPresets: Boolean = false) {
        ioExecutor.execute {
            if (seedPresets) {
                PresetPrompts.ensureSeeded(repository)
            }
            val prompts = repository.list()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                renderPrompts(prompts)
            }
        }
    }

    private fun renderPrompts(prompts: List<Prompt>) {
        allPrompts = prompts
        applyFilter(binding.promptSearchInput.text?.toString().orEmpty())
    }

    /** 本地过滤全量指令；区分「没有指令」与「没有匹配」两种空态。 */
    private fun applyFilter(query: String) {
        val filtered = PromptFilter.filter(allPrompts, query)
        adapter.submit(filtered)
        binding.emptyText.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        binding.emptyText.setText(
            if (allPrompts.isEmpty()) {
                R.string.prompt_library_empty
            } else {
                R.string.prompt_search_no_match
            },
        )
        binding.countLabel.text = getString(R.string.prompt_count_label, filtered.size)
    }

    /** 点击指令：记录一次使用，把标题与正文回传给主页。 */
    private fun returnPrompt(prompt: Prompt) {
        ioExecutor.execute { repository.incrementUsage(prompt.id) }
        setResult(
            RESULT_OK,
            Intent().putExtra(EXTRA_PROMPT_TITLE, prompt.title)
                .putExtra(EXTRA_PROMPT_CONTENT, prompt.content),
        )
        finish()
    }

    private fun showPromptActions(prompt: Prompt, anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_EDIT, 0, R.string.prompt_edit)
            menu.add(0, MENU_DELETE, 1, R.string.prompt_delete)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_EDIT -> {
                        showEditDialog(prompt)
                        true
                    }

                    MENU_DELETE -> {
                        confirmDelete(prompt)
                        true
                    }

                    else -> false
                }
            }
        }.show()
    }

    /** 新增 / 编辑指令对话框。prompt 为 null 表示新增。 */
    private fun showEditDialog(prompt: Prompt?) {
        val isEdit = prompt != null
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dpToPx(20)
            setPadding(pad, dpToPx(8), pad, 0)
        }
        val titleInput = EditText(this).apply {
            hint = getString(R.string.prompt_field_title)
            setText(prompt?.title.orEmpty())
            setSelection(text.length)
            maxLines = 1
        }
        val contentInput = EditText(this).apply {
            hint = getString(R.string.prompt_field_content)
            setText(prompt?.content.orEmpty())
            setSelection(text.length)
            minLines = 4
            gravity = Gravity.START or Gravity.TOP
        }
        container.addView(titleInput)
        container.addView(contentInput)

        AlertDialog.Builder(this)
            .setTitle(
                if (isEdit) {
                    R.string.prompt_edit_title_edit
                } else {
                    R.string.prompt_edit_title_new
                },
            )
            .setView(container)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                val (title, content) = PromptFormatter.normalize(
                    titleInput.text?.toString().orEmpty(),
                    contentInput.text?.toString().orEmpty(),
                )
                if (!PromptFormatter.isValidTitle(title)) {
                    CompatToast.show(this, R.string.prompt_empty_title)
                    return@setPositiveButton
                }
                if (!PromptFormatter.isValidContent(content)) {
                    CompatToast.show(this, R.string.prompt_empty_content)
                    return@setPositiveButton
                }
                if (isEdit) {
                    ioExecutor.execute {
                        repository.update(prompt!!.copy(title = title, content = content))
                        reloadQuietly()
                    }
                } else {
                    ioExecutor.execute {
                        repository.add(title, content)
                        reloadQuietly()
                    }
                }
                CompatToast.show(this, R.string.prompt_saved)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(prompt: Prompt) {
        AlertDialog.Builder(this)
            .setTitle(R.string.prompt_delete_confirm_title)
            .setMessage(getString(R.string.prompt_delete_confirm, prompt.title))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                ioExecutor.execute {
                    repository.delete(prompt.id)
                    reloadQuietly()
                }
                CompatToast.show(this, R.string.prompt_deleted)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 写操作后重新读取列表（不再注入预置）。 */
    private fun reloadQuietly() {
        val prompts = repository.list()
        runOnUiThread {
            if (isFinishing) return@runOnUiThread
            renderPrompts(prompts)
        }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        ioExecutor.shutdownNow()
        repository.close()
        super.onDestroy()
    }

    companion object {
        /** 回传给主页的 Extra：指令标题。 */
        const val EXTRA_PROMPT_TITLE = "com.chatyue.chat.PROMPT_TITLE"

        /** 回传给主页的 Extra：指令正文（填入输入框）。 */
        const val EXTRA_PROMPT_CONTENT = "com.chatyue.chat.PROMPT_CONTENT"

        private const val MENU_EDIT = 1
        private const val MENU_DELETE = 2
    }
}
