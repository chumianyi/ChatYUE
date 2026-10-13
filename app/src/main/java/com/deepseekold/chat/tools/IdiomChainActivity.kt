package com.deepseekold.chat.tools

import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import androidx.core.content.getSystemService
import com.deepseekold.chat.ChatMessage
import com.deepseekold.chat.ChatMessageAdapter
import com.deepseekold.chat.MessageRole
import com.deepseekold.chat.R
import com.deepseekold.chat.backend.BackendMessage
import com.deepseekold.chat.backend.BackendRole
import com.deepseekold.chat.backend.CancellableRequest
import com.deepseekold.chat.databinding.ActivityToolIdiomChainBinding

/**
 * 成语接龙：AI 随机出题 → 用户接龙 → AI 判定合法性并接续；
 * 首字读音与上一个成语末字相同即算合法，AI 接不上则游戏结束。
 */
class IdiomChainActivity : BaseToolActivity() {
    private lateinit var binding: ActivityToolIdiomChainBinding
    private lateinit var adapter: ChatMessageAdapter

    private val messages = mutableListOf<ChatMessage>()
    private val judgeHistory = mutableListOf<BackendMessage>()
    private var request: CancellableRequest? = null
    private var currentIdiom: String? = null
    private var busy = false
    private var gameOver = false
    private var nextMessageId = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityToolIdiomChainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupBackButton(binding.backButton)
        adapter = setupMessageList(binding.messageList)

        binding.startButton.setOnClickListener { startGame() }
        binding.submitButton.setOnClickListener { submitIdiom() }
        refreshControls()
    }

    override fun onDestroy() {
        request?.cancel()
        super.onDestroy()
    }

    // ---------------- 开局 ----------------

    private fun startGame() {
        if (busy) return
        request?.cancel()
        busy = true
        gameOver = false
        currentIdiom = null
        judgeHistory.clear()
        messages.clear()
        adapter.submitMessages(emptyList())
        binding.currentIdiomText.setText(R.string.idiom_none)
        binding.turnHint.text = getString(R.string.idiom_starting)
        refreshControls()

        val buffer = StringBuilder()
        request = ToolApi.chat(
            messages = listOf(
                BackendMessage(BackendRole.SYSTEM, ToolPrompts.IDIOM_OPENING_SYSTEM),
                BackendMessage(BackendRole.USER, "请给出起始成语。"),
            ),
            onTextDelta = { buffer.append(it) },
            onCompleted = { content ->
                request = null
                busy = false
                val text = buffer.toString().ifEmpty { content }
                val idiom = extractIdiom(text)
                if (idiom == null) {
                    binding.turnHint.text = ""
                    longToast(getString(R.string.soup_puzzle_failed))
                } else {
                    judgeHistory += BackendMessage(
                        BackendRole.SYSTEM,
                        ToolPrompts.IDIOM_JUDGE_SYSTEM,
                    )
                    startTurnWith(idiom)
                }
                refreshControls()
            },
            onFailure = { message ->
                request = null
                busy = false
                binding.turnHint.text = ""
                longToast(message)
                refreshControls()
            },
        )
    }

    private fun startTurnWith(idiom: String) {
        currentIdiom = idiom
        binding.currentIdiomText.text = idiom
        binding.turnHint.text = getString(R.string.idiom_your_turn, idiom)
        appendMessage(MessageRole.ASSISTANT, "🎲 起始成语：$idiom")
    }

    // ---------------- 接龙 ----------------

    private fun submitIdiom() {
        val previous = currentIdiom ?: run {
            toast(getString(R.string.idiom_need_start))
            return
        }
        if (busy || gameOver) return

        val input = binding.idiomInput.text?.toString()?.trim().orEmpty()
        if (input.isEmpty()) {
            toast(getString(R.string.idiom_empty_input))
            return
        }
        binding.idiomInput.text?.clear()
        getSystemService<InputMethodManager>()?.hideSoftInputFromWindow(
            binding.idiomInput.windowToken,
            0,
        )

        appendMessage(MessageRole.USER, input)
        val placeholderId = appendMessage(MessageRole.ASSISTANT, getString(R.string.generating))
        busy = true
        refreshControls()

        request = ToolApi.chat(
            messages = ToolPrompts.idiomJudgeMessages(judgeHistory.toList(), previous, input),
            onThinkingDelta = {},
            onTextDelta = {},
            onCompleted = { content ->
                request = null
                busy = false
                handleJudgeReply(placeholderId, previous, input, content)
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

    private fun handleJudgeReply(
        placeholderId: Long,
        previous: String,
        playerIdiom: String,
        raw: String,
    ) {
        val lines = raw.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.removePrefix("-").trim() }
        val verdict = lines.getOrNull(0)?.uppercase().orEmpty()
        val valid = verdict.startsWith("VALID") && !verdict.startsWith("INVALID")
        val nextRaw = lines.getOrNull(1).orEmpty()
        val comment = lines.getOrNull(2).orEmpty()
        val nextIdiom = extractIdiom(nextRaw)

        if (!valid) {
            gameOver = true
            updateMessage(
                placeholderId,
                "❌ 「$playerIdiom」不符合规则" + if (comment.isNotEmpty()) "：$comment" else "",
            )
            appendMessage(MessageRole.ASSISTANT, getString(R.string.idiom_game_over))
            return
        }

        if (nextIdiom == null) {
            gameOver = true
            updateMessage(placeholderId, "✅ 「$playerIdiom」合法")
            appendMessage(MessageRole.ASSISTANT, getString(R.string.idiom_ai_give_up))
            return
        }

        judgeHistory += BackendMessage(BackendRole.USER, "上一个成语：$previous\n玩家接的成语：$playerIdiom")
        judgeHistory += BackendMessage(BackendRole.ASSISTANT, raw)

        updateMessage(
            placeholderId,
            "✅ 「$playerIdiom」合法" + if (comment.isNotEmpty()) "（$comment）" else "",
        )
        appendMessage(MessageRole.ASSISTANT, getString(R.string.idiom_ai_next, nextIdiom))
        currentIdiom = nextIdiom
        binding.currentIdiomText.text = nextIdiom
        binding.turnHint.text = getString(R.string.idiom_your_turn, nextIdiom)
    }

    /** 从模型输出中提取四字成语（去掉括号说明，取最后一段四字汉字）。 */
    private fun extractIdiom(raw: String): String? {
        if (raw.isBlank()) return null
        val trimmed = raw.trim()
        if (trimmed.uppercase().replace(" ", "").startsWith("NONE")) return null
        val withoutNotes = trimmed.replace(
            Regex("[（(【\\[][^）)】\\]]*[）)】\\]]"),
            " ",
        )
        val matches = IDIOM_PATTERN.findAll(withoutNotes).toList()
        if (matches.isEmpty()) return null
        return matches.last().value
    }

    // ---------------- 界面辅助 ----------------

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
        val playing = currentIdiom != null && !gameOver
        binding.submitButton.isEnabled = playing && !busy
        binding.idiomInput.isEnabled = playing && !busy
        binding.startButton.isEnabled = !busy
        binding.startButton.setText(
            if (currentIdiom != null) R.string.idiom_restart else R.string.idiom_start,
        )
    }

    companion object {
        private const val TOOL_CONVERSATION_ID = 0L
        private val IDIOM_PATTERN = Regex("[\\u4e00-\\u9fff]{4}")
    }
}
