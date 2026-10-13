package com.deepseekold.chat.tools

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import com.deepseekold.chat.R
import com.deepseekold.chat.backend.CancellableRequest
import com.deepseekold.chat.databinding.ActivityToolTranslationBinding

/**
 * 智能翻译：5 组互译（中文↔英文 / 韩文 / 日文，简体↔繁体，中文↔俄文）。
 * 翻译方向通过系统提示词指定，AI 只输出译文。
 */
class TranslationActivity : BaseToolActivity() {
    private lateinit var binding: ActivityToolTranslationBinding
    private var request: CancellableRequest? = null
    private var streaming = false
    private var leftToRight = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityToolTranslationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupBackButton(binding.backButton)

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            ToolPrompts.TRANSLATION_PAIRS.map { it.label() },
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.pairSpinner.adapter = adapter
        binding.pairSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                updateDirectionLabel()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.swapButton.setOnClickListener {
            leftToRight = !leftToRight
            updateDirectionLabel()
        }
        binding.translateButton.setOnClickListener {
            if (streaming) {
                cancelStreaming()
            } else {
                startTranslation()
            }
        }
        updateDirectionLabel()
    }

    override fun onDestroy() {
        request?.cancel()
        super.onDestroy()
    }

    private fun currentPair(): ToolPrompts.TranslationPair {
        val index = binding.pairSpinner.selectedItemPosition
            .coerceIn(0, ToolPrompts.TRANSLATION_PAIRS.lastIndex)
        return ToolPrompts.TRANSLATION_PAIRS[index]
    }

    private fun updateDirectionLabel() {
        binding.directionLabel.text = currentPair().direction(leftToRight)
    }

    private fun startTranslation() {
        val source = binding.sourceInput.text?.toString()?.trim().orEmpty()
        if (source.isEmpty()) {
            toast(getString(R.string.translate_empty_input))
            return
        }

        val direction = currentPair().direction(leftToRight)
        val thinking = StringBuilder()
        val answer = StringBuilder()
        binding.resultText.text = ""
        setStreaming(true)

        request = ToolApi.chat(
            messages = ToolPrompts.translationMessages(direction, source),
            onTextDelta = { delta ->
                answer.append(delta)
                binding.resultText.text = answer.toString()
            },
            onThinkingDelta = { delta ->
                thinking.append(delta)
                // 正式译文还没开始时，先展示思考过程作为进度反馈。
                if (answer.isEmpty()) {
                    binding.resultText.text = thinking.toString()
                }
            },
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
        binding.translateButton.isEnabled = true
        binding.translateButton.setText(
            if (isStreaming) R.string.generating else R.string.translate_start,
        )
        binding.pairSpinner.isEnabled = !isStreaming
        binding.swapButton.isEnabled = !isStreaming
    }
}
