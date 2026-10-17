package com.chatyue.chat.tools

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import com.chatyue.chat.R
import com.chatyue.chat.backend.BackendMessage
import com.chatyue.chat.backend.BackendRole
import com.chatyue.chat.backend.CancellableRequest
import com.chatyue.chat.databinding.ActivityToolPromptBinding

/**
 * 通用提示词工具页：输入文本 → 调用模型流式生成 → Markdown 渲染结果。
 *
 * 由抽屉里的具体工具（代码解释 / 文本润色 / 文本摘要）通过 Intent 配置后启动；
 * 可选的「风格」下拉会拼进系统提示词，让同一个工具支持多种输出风格。
 */
class PromptToolActivity : BaseToolActivity() {
    private lateinit var binding: ActivityToolPromptBinding
    private var request: CancellableRequest? = null
    private var streaming = false

    /** 配置：来自启动 Intent。 */
    private lateinit var toolTitle: String
    private lateinit var baseSystemPrompt: String
    private lateinit var optionLabels: List<String>
    private lateinit var optionPrompts: List<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityToolPromptBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupBackButton(binding.backButton)

        toolTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        baseSystemPrompt = intent.getStringExtra(EXTRA_SYSTEM_PROMPT).orEmpty()
        optionLabels = intent.getStringArrayListExtra(EXTRA_OPTION_LABELS).orEmpty()
        optionPrompts = intent.getStringArrayListExtra(EXTRA_OPTION_PROMPTS).orEmpty()

        binding.titleText.text = toolTitle
        binding.sourceInput.hint = intent.getStringExtra(EXTRA_PLACEHOLDER)
        binding.actionButton.setText(intent.getIntExtra(EXTRA_ACTION_LABEL, R.string.tool_start))

        if (optionLabels.isNotEmpty() && optionLabels.size == optionPrompts.size) {
            setupOptionSpinner(binding.optionSpinner)
        }

        binding.actionButton.setOnClickListener {
            if (streaming) cancelStreaming() else startGeneration()
        }
    }

    override fun onDestroy() {
        request?.cancel()
        super.onDestroy()
    }

    private fun setupOptionSpinner(spinner: Spinner) {
        spinner.visibility = View.VISIBLE
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            optionLabels,
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
    }

    private fun currentOptionPrompt(): String? {
        if (optionLabels.isEmpty()) return null
        val index = binding.optionSpinner.selectedItemPosition
            .coerceIn(0, optionPrompts.lastIndex)
        return optionPrompts[index].takeIf { it.isNotBlank() }
    }

    private fun startGeneration() {
        val source = binding.sourceInput.text?.toString()?.trim().orEmpty()
        if (source.isEmpty()) {
            toast(getString(R.string.tool_empty_input))
            return
        }
        val option = currentOptionPrompt()
        val systemPrompt = if (option != null) {
            "$baseSystemPrompt\n$option"
        } else {
            baseSystemPrompt
        }

        val answer = StringBuilder()
        binding.resultText.setText(R.string.generating)
        setStreaming(true)

        request = ToolApi.chat(
            messages = listOf(
                BackendMessage(BackendRole.SYSTEM, systemPrompt),
                BackendMessage(BackendRole.USER, source),
            ),
            onTextDelta = { delta ->
                answer.append(delta)
                binding.resultText.text = answer.toString()
            },
            onThinkingDelta = {},
            onCompleted = { content ->
                request = null
                setStreaming(false)
                val text = answer.toString().ifEmpty { content }
                markwon.setMarkdown(binding.resultText, text)
            },
            onFailure = { message ->
                request = null
                setStreaming(false)
                binding.resultText.text = ""
                longToast(message)
            },
        )
    }

    private fun cancelStreaming() {
        request?.cancel()
        request = null
        setStreaming(false)
    }

    private fun setStreaming(isStreaming: Boolean) {
        streaming = isStreaming
        binding.actionButton.setText(
            if (isStreaming) R.string.generating else R.string.tool_start,
        )
        binding.sourceInput.isEnabled = !isStreaming
        binding.optionSpinner.isEnabled = !isStreaming
    }

    companion object {
        const val EXTRA_TITLE = "com.chatyue.chat.TOOL_TITLE"
        const val EXTRA_SYSTEM_PROMPT = "com.chatyue.chat.TOOL_SYSTEM_PROMPT"
        const val EXTRA_PLACEHOLDER = "com.chatyue.chat.TOOL_PLACEHOLDER"
        const val EXTRA_ACTION_LABEL = "com.chatyue.chat.TOOL_ACTION_LABEL"
        const val EXTRA_OPTION_LABELS = "com.chatyue.chat.TOOL_OPTION_LABELS"
        const val EXTRA_OPTION_PROMPTS = "com.chatyue.chat.TOOL_OPTION_PROMPTS"

        /** 构造启动 Intent 的便捷方法，供抽屉入口调用。 */
        fun intent(
            context: android.content.Context,
            title: String,
            systemPrompt: String,
            placeholder: String,
            actionLabel: Int,
            optionLabels: List<String> = emptyList(),
            optionPrompts: List<String> = emptyList(),
        ): Intent =
            Intent(context, PromptToolActivity::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_SYSTEM_PROMPT, systemPrompt)
                putExtra(EXTRA_PLACEHOLDER, placeholder)
                putExtra(EXTRA_ACTION_LABEL, actionLabel)
                putStringArrayListExtra(EXTRA_OPTION_LABELS, ArrayList(optionLabels))
                putStringArrayListExtra(EXTRA_OPTION_PROMPTS, ArrayList(optionPrompts))
            }
    }
}
