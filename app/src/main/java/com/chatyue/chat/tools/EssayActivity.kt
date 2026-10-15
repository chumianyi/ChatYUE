package com.chatyue.chat.tools

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.SeekBar
import com.chatyue.chat.ContextBudget
import com.chatyue.chat.R
import com.chatyue.chat.backend.CancellableRequest
import com.chatyue.chat.databinding.ActivityToolEssayBinding

/**
 * 一起作文：用户输入题目/要求，滑块选择 100~2000 字，AI 生成作文。
 */
class EssayActivity : BaseToolActivity() {
    private lateinit var binding: ActivityToolEssayBinding
    private var request: CancellableRequest? = null
    private var streaming = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityToolEssayBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupBackButton(binding.backButton)

        val styleAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            ToolPrompts.ESSAY_STYLES.map { it.name },
        )
        styleAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.styleSpinner.adapter = styleAdapter

        val languageAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            ToolPrompts.ESSAY_LANGUAGES.map { it.name },
        )
        languageAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.languageSpinner.adapter = languageAdapter
        // 默认中文（ESSAY_LANGUAGES 第 0 项）
        binding.languageSpinner.setSelection(DEFAULT_LANGUAGE_INDEX)

        binding.wordCountSlider.max = SLIDER_STEPS
        binding.wordCountSlider.progress = DEFAULT_STEP
        binding.wordCountSlider.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean,
                ) {
                    updateWordCountLabel()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            },
        )
        binding.generateButton.setOnClickListener {
            if (streaming) {
                cancelStreaming()
            } else {
                startEssay()
            }
        }
        updateWordCountLabel()
    }

    override fun onDestroy() {
        request?.cancel()
        super.onDestroy()
    }

    private fun targetWords(): Int =
        ToolPrompts.ESSAY_MIN_WORDS + binding.wordCountSlider.progress * SLIDER_STEP

    private fun updateWordCountLabel() {
        binding.wordCountLabel.text =
            getString(R.string.essay_word_count, targetWords())
    }

    private fun startEssay() {
        val topic = binding.topicInput.text?.toString()?.trim().orEmpty()
        if (topic.isEmpty()) {
            toast(getString(R.string.essay_empty_topic))
            return
        }

        val target = targetWords()
        val style = currentStyle()
        val language = currentLanguage()
        val answer = StringBuilder()
        binding.resultText.setText(R.string.generating)
        binding.resultCountLabel.text = ""
        setStreaming(true)

        request = ToolApi.chat(
            messages = ToolPrompts.essayMessages(topic, target, style, language),
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
                binding.resultCountLabel.text =
                    getString(R.string.essay_result_count, countWords(text))
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
        val text = binding.resultText.text?.toString().orEmpty()
        binding.resultCountLabel.text =
            getString(R.string.essay_result_count, countWords(text))
    }

    private fun setStreaming(isStreaming: Boolean) {
        streaming = isStreaming
        binding.generateButton.isEnabled = true
        binding.generateButton.setText(
            if (isStreaming) R.string.generating else R.string.essay_generate,
        )
        binding.wordCountSlider.isEnabled = !isStreaming
        binding.styleSpinner.isEnabled = !isStreaming
        binding.languageSpinner.isEnabled = !isStreaming
    }

    private fun currentStyle(): ToolPrompts.EssayStyle {
        val index = binding.styleSpinner.selectedItemPosition
            .coerceIn(0, ToolPrompts.ESSAY_STYLES.lastIndex)
        return ToolPrompts.ESSAY_STYLES[index]
    }

    private fun currentLanguage(): ToolPrompts.EssayLanguage {
        val index = binding.languageSpinner.selectedItemPosition
            .coerceIn(0, ToolPrompts.ESSAY_LANGUAGES.lastIndex)
        return ToolPrompts.ESSAY_LANGUAGES[index]
    }

    /** 统计正文实际字数（不含空白字符）。 */
    private fun countWords(text: String): Int {
        var count = 0
        for (char in text) {
            if (char.isWhitespace()) continue
            if (ContextBudget.isCjk(char) || char.isLetterOrDigit()) count++
        }
        return count
    }

    companion object {
        private const val SLIDER_STEP = 50
        private const val SLIDER_STEPS =
            (ToolPrompts.ESSAY_MAX_WORDS - ToolPrompts.ESSAY_MIN_WORDS) / SLIDER_STEP
        private const val DEFAULT_STEP = 14
        private const val DEFAULT_LANGUAGE_INDEX = 0
    }
}
