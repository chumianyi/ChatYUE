package com.deepseekold.chat.tools

import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AlertDialog
import androidx.core.content.getSystemService
import com.deepseekold.chat.ChatMessage
import com.deepseekold.chat.ChatMessageAdapter
import com.deepseekold.chat.MessageRole
import com.deepseekold.chat.R
import com.deepseekold.chat.backend.BackendMessage
import com.deepseekold.chat.backend.BackendRole
import com.deepseekold.chat.backend.CancellableRequest
import com.deepseekold.chat.databinding.ActivityToolTurtleSoupBinding
import org.json.JSONObject

/**
 * 海龟汤：AI 以 JSON 形式生成"谜面 + 谜底"，前端只展示谜面；
 * 用户提问时 AI 只回答「是」「否」「提示」，提示机会共 3 次，猜中谜底即获胜。
 */
class TurtleSoupActivity : BaseToolActivity() {
    private lateinit var binding: ActivityToolTurtleSoupBinding
    private lateinit var adapter: ChatMessageAdapter

    private val messages = mutableListOf<ChatMessage>()
    private val playHistory = mutableListOf<BackendMessage>()
    private var request: CancellableRequest? = null
    private var puzzle: String? = null
    private var answer: String? = null
    private var hintsLeft = ToolPrompts.SOUP_HINT_LIMIT
    private var busy = false
    private var solved = false
    private var nextMessageId = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityToolTurtleSoupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupBackButton(binding.backButton)
        adapter = setupMessageList(binding.messageList)

        binding.startButton.setOnClickListener { startPuzzle() }
        binding.askButton.setOnClickListener { askQuestion() }
        binding.hintButton.setOnClickListener { requestHint() }
        binding.guessButton.setOnClickListener { submitGuess() }

        refreshControls()
    }

    override fun onDestroy() {
        request?.cancel()
        super.onDestroy()
    }

    // ---------------- 出题 ----------------

    private fun startPuzzle() {
        if (busy) return
        request?.cancel()
        busy = true
        solved = false
        puzzle = null
        answer = null
        hintsLeft = ToolPrompts.SOUP_HINT_LIMIT
        playHistory.clear()
        messages.clear()
        adapter.submitMessages(emptyList())
        binding.puzzleText.setText(R.string.soup_puzzle_placeholder)
        refreshControls()

        val buffer = StringBuilder()
        binding.puzzleText.text = getString(R.string.generating)
        request = ToolApi.chat(
            messages = listOf(
                BackendMessage(BackendRole.SYSTEM, ToolPrompts.SOUP_PUZZLE_SYSTEM),
                BackendMessage(BackendRole.USER, "请给出一个海龟汤谜题。"),
            ),
            onTextDelta = { buffer.append(it) },
            onCompleted = { content ->
                request = null
                busy = false
                val text = buffer.toString().ifEmpty { content }
                val parsed = parsePuzzle(text)
                if (parsed == null) {
                    binding.puzzleText.setText(R.string.soup_puzzle_placeholder)
                    longToast(getString(R.string.soup_puzzle_failed))
                } else {
                    puzzle = parsed.first
                    answer = parsed.second
                    binding.puzzleText.text = parsed.first
                    playHistory += BackendMessage(
                        BackendRole.SYSTEM,
                        ToolPrompts.soupPlaySystem(parsed.first, parsed.second),
                    )
                    appendMessage(MessageRole.ASSISTANT, getString(R.string.soup_turn_hint))
                    refreshControls()
                }
            },
            onFailure = { message ->
                request = null
                busy = false
                binding.puzzleText.setText(R.string.soup_puzzle_placeholder)
                longToast(message)
                refreshControls()
            },
        )
    }

    private fun parsePuzzle(raw: String): Pair<String, String>? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start >= 0 && end > start) {
            runCatching {
                val json = JSONObject(raw.substring(start, end + 1))
                val puzzleText = json.optString("puzzle").trim()
                val answerText = json.optString("answer").trim()
                if (puzzleText.isNotEmpty() && answerText.isNotEmpty()) {
                    return puzzleText to answerText
                }
            }
        }
        // 模型偶尔不按 JSON 输出时，退化为按关键字切分。
        val fallbackPuzzle = raw.substringAfter("谜面", "").substringBefore("谜底").trim()
        val fallbackAnswer = raw.substringAfter("谜底", "").trim()
        if (fallbackPuzzle.isNotEmpty() && fallbackAnswer.isNotEmpty()) {
            return fallbackPuzzle to fallbackAnswer
        }
        return null
    }

    // ---------------- 提问 / 提示 / 猜谜底 ----------------

    private fun askQuestion() {
        val question = consumeInput() ?: return
        if (puzzle == null) {
            toast(getString(R.string.soup_need_puzzle))
            return
        }
        if (busy || solved) return

        appendMessage(MessageRole.USER, question)
        playHistory += BackendMessage(BackendRole.USER, question)
        sendPlayTurn()
    }

    private fun requestHint() {
        if (puzzle == null) {
            toast(getString(R.string.soup_need_puzzle))
            return
        }
        if (busy || solved) return
        if (hintsLeft <= 0) {
            toast(getString(R.string.soup_hints_exhausted))
            return
        }

        hintsLeft -= 1
        appendMessage(MessageRole.USER, "请给我一个提示")
        playHistory += BackendMessage(
            BackendRole.USER,
            "请给我一个提示，只输出一句话，不要泄露谜底。",
        )
        refreshControls()
        sendPlayTurn()
    }

    private fun submitGuess() {
        val guess = consumeInput() ?: return
        val puzzleText = puzzle ?: run {
            toast(getString(R.string.soup_need_puzzle))
            return
        }
        val answerText = answer ?: return
        if (busy || solved) return

        appendMessage(MessageRole.USER, "我猜谜底是：$guess")
        val placeholderId = appendMessage(MessageRole.ASSISTANT, getString(R.string.generating))
        busy = true
        refreshControls()

        request = ToolApi.chat(
            messages = listOf(
                BackendMessage(
                    BackendRole.SYSTEM,
                    ToolPrompts.soupGuessSystem(puzzleText, answerText),
                ),
                BackendMessage(BackendRole.USER, "我的猜测：$guess"),
            ),
            onThinkingDelta = {},
            onTextDelta = {},
            onCompleted = { content ->
                request = null
                busy = false
                val reply = content.trim()
                updateMessage(placeholderId, reply.ifEmpty { "否" })
                if (reply.contains("正确")) {
                    solved = true
                    appendMessage(
                        MessageRole.ASSISTANT,
                        getString(R.string.soup_answer_reveal, answerText),
                    )
                    showVictory(answerText)
                }
                refreshControls()
            },
            onFailure = { message ->
                request = null
                busy = false
                updateMessage(placeholderId, message)
                refreshControls()
            },
        )
    }

    private fun sendPlayTurn() {
        val placeholderId = appendMessage(MessageRole.ASSISTANT, getString(R.string.generating))
        busy = true
        refreshControls()

        request = ToolApi.chat(
            messages = playHistory.toList(),
            onThinkingDelta = {},
            onTextDelta = {},
            onCompleted = { content ->
                request = null
                busy = false
                val reply = content.trim().ifEmpty { "否" }
                updateMessage(placeholderId, reply)
                playHistory += BackendMessage(BackendRole.ASSISTANT, reply)
                refreshControls()
            },
            onFailure = { message ->
                request = null
                busy = false
                updateMessage(placeholderId, message)
                refreshControls()
            },
        )
    }

    private fun showVictory(answerText: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.soup_victory_title)
            .setMessage(getString(R.string.soup_victory_message, answerText))
            .setPositiveButton(R.string.soup_start) { _, _ -> startPuzzle() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------- 界面辅助 ----------------

    private fun consumeInput(): String? {
        val text = binding.questionInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            toast(getString(R.string.soup_input_hint))
            return null
        }
        binding.questionInput.text?.clear()
        getSystemService<InputMethodManager>()?.hideSoftInputFromWindow(
            binding.questionInput.windowToken,
            0,
        )
        return text
    }

    private fun appendMessage(role: MessageRole, content: String): Long {
        val id = nextMessageId--
        messages += ChatMessage(
            id = id,
            conversationId = TOOL_CONVERSATION_ID,
            role = role,
            content = content,
            createdAt = id,
        )
        adapter.submitMessages(messages.toList())
        scrollListToBottom(binding.messageList, adapter)
        return id
    }

    private fun updateMessage(id: Long, content: String) {
        val index = messages.indexOfFirst { it.id == id }
        if (index < 0) return
        messages[index] = messages[index].copy(content = content)
        adapter.submitMessages(messages.toList())
        scrollListToBottom(binding.messageList, adapter)
    }

    private fun refreshControls() {
        val hasPuzzle = puzzle != null
        binding.askButton.isEnabled = hasPuzzle && !busy && !solved
        binding.guessButton.isEnabled = hasPuzzle && !busy && !solved
        binding.hintButton.isEnabled = hasPuzzle && !busy && !solved && hintsLeft > 0
        binding.questionInput.isEnabled = hasPuzzle && !solved
        binding.startButton.isEnabled = !busy
        binding.startButton.setText(
            if (hasPuzzle) R.string.soup_restart else R.string.soup_start,
        )
        binding.hintButton.text = getString(R.string.soup_hint_button, hintsLeft)
        binding.hintCounter.text = getString(
            R.string.soup_hint_counter,
            hintsLeft,
            ToolPrompts.SOUP_HINT_LIMIT,
        )
    }

    companion object {
        private const val TOOL_CONVERSATION_ID = 0L
    }
}
