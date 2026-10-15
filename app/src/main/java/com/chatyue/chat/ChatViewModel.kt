package com.chatyue.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.chatyue.chat.backend.BackendAssistantMessage
import com.chatyue.chat.backend.BackendError
import com.chatyue.chat.backend.BackendErrorType
import com.chatyue.chat.backend.BackendMessage
import com.chatyue.chat.backend.BackendRole
import com.chatyue.chat.backend.CancellableRequest
import com.chatyue.chat.backend.ChatBackend
import com.chatyue.chat.backend.ChatRequest
import com.chatyue.chat.backend.DeepSeekChatBackend
import java.util.concurrent.atomic.AtomicBoolean

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    val repository = LocalChatRepository(application)

    private val backend: ChatBackend = DeepSeekChatBackend()
    private val mutableGenerationState = MutableLiveData<GenerationState>(GenerationState.Idle)
    val generationState: LiveData<GenerationState> = mutableGenerationState

    private val mutableContextEvent = MutableLiveData<ContextEvent?>(null)
    val contextEvent: LiveData<ContextEvent?> = mutableContextEvent

    private var activeRequest: CancellableRequest? = null
    private var compressionRequest: CancellableRequest? = null
    private val compressing = AtomicBoolean(false)
    private var lastCompressedConversationId: Long? = null
    private var lastCompressedMessageCount = 0
    private var nextEventId = 1L

    init {
        repository.finalizeInterruptedThinking()
    }

    fun sendMessage(request: ChatRequest) {
        cancelGeneration()
        val conversationId = request.conversationId?.toLongOrNull()
            ?: return publishFailure(
                null,
                BackendError(
                    message = "当前会话无效。",
                    type = BackendErrorType.PROTOCOL,
                ),
            )
        val thinking = StringBuilder()
        val content = StringBuilder()
        publishActive(conversationId, thinking, content)

        activeRequest = backend.sendMessage(
            request,
            object : ChatBackend.Callback {
                override fun onThinkingDelta(text: String) {
                    thinking.append(text)
                    publishActive(conversationId, thinking, content)
                }

                override fun onTextDelta(text: String) {
                    content.append(text)
                    publishActive(conversationId, thinking, content)
                }

                override fun onCompleted(message: BackendAssistantMessage) {
                    activeRequest = null
                    if (message.thinking?.isNotBlank() == true) {
                        repository.addMessage(
                            conversationId,
                            MessageRole.THINKING_FINISHED,
                            message.thinking,
                        )
                    }
                    if (message.content.isNotBlank()) {
                        repository.addMessage(
                            conversationId,
                            MessageRole.ASSISTANT,
                            message.content,
                        )
                    }

                    if (message.content.isBlank() && message.thinking.isNullOrBlank()) {
                        publishFailure(
                            conversationId,
                            BackendError(
                                message = "服务端没有返回可显示的内容。",
                                type = BackendErrorType.PROTOCOL,
                                retryable = true,
                            ),
                        )
                    } else {
                        mutableGenerationState.value = GenerationState.Completed(
                            eventId = nextEventId++,
                            conversationId = conversationId,
                            message = message,
                        )
                        maybeCompressContext(conversationId)
                    }
                }

                override fun onFailure(error: BackendError) {
                    activeRequest = null
                    publishFailure(conversationId, error)
                }
            },
        )
    }

    fun cancelGeneration() {
        activeRequest?.cancel()
        activeRequest = null
        mutableGenerationState.value = GenerationState.Idle
    }

    fun acknowledgeTerminal(eventId: Long) {
        val state = mutableGenerationState.value
        if (state is GenerationState.Terminal && state.eventId == eventId) {
            mutableGenerationState.value = GenerationState.Idle
        }
    }

    fun acknowledgeContextEvent() {
        mutableContextEvent.value = null
    }

    override fun onCleared() {
        activeRequest?.cancel()
        compressionRequest?.cancel()
        repository.close()
        super.onCleared()
    }

    /**
     * 上下文用量达到阈值（85%）时，自动调用 AI 把前半段对话压缩成一条 system 摘要，
     * 并用这条摘要替换掉被压缩的原始消息。
     */
    private fun maybeCompressContext(conversationId: Long) {
        if (compressing.get()) return

        val messages = repository.getMessages(conversationId)
        if (!ContextBudget.needsCompression(messages)) return

        val compressible = messages.filter { it.role.isContextMessage }
        if (compressible.size < MIN_MESSAGES_TO_COMPRESS) return
        if (lastCompressedConversationId == conversationId &&
            compressible.size <= lastCompressedMessageCount
        ) {
            return
        }
        if (!compressing.compareAndSet(false, true)) return

        val head = compressible.subList(0, compressible.size / 2).toList()
        val percent = ContextBudget.percentOf(messages)
        mutableContextEvent.value = ContextEvent.CompressionStarted(percent)

        compressionRequest = backend.sendMessage(
            ChatRequest(
                conversationId = null,
                messages = listOf(
                    BackendMessage(BackendRole.SYSTEM, Prompts.COMPRESSION_SYSTEM),
                    BackendMessage(
                        BackendRole.USER,
                        Prompts.compressionUserPrompt(renderTranscript(head)),
                    ),
                ),
            ),
            object : ChatBackend.Callback {
                override fun onThinkingDelta(text: String) = Unit

                override fun onTextDelta(text: String) = Unit

                override fun onCompleted(message: BackendAssistantMessage) {
                    compressionRequest = null
                    compressing.set(false)
                    val summary = message.content.trim()
                    if (summary.isEmpty()) {
                        mutableContextEvent.value = ContextEvent.CompressionFailed(
                            "上下文压缩失败：摘要内容为空。",
                        )
                        return
                    }
                    repository.replaceMessagesWithSystemSummary(
                        conversationId = conversationId,
                        removedMessageIds = head.map { it.id },
                        summary = summary,
                    )
                    lastCompressedConversationId = conversationId
                    lastCompressedMessageCount = repository.getMessages(conversationId)
                        .count { it.role.isContextMessage }
                    mutableContextEvent.value = ContextEvent.CompressionFinished(
                        ContextBudget.percentOf(repository.getMessages(conversationId)),
                    )
                }

                override fun onFailure(error: BackendError) {
                    compressionRequest = null
                    compressing.set(false)
                    mutableContextEvent.value = ContextEvent.CompressionFailed(
                        error.message.ifBlank { "上下文压缩失败。" },
                    )
                }
            },
        )
    }

    private fun renderTranscript(messages: List<ChatMessage>): String {
        return messages.joinToString(separator = "\n\n") { message ->
            val speaker = when (message.role) {
                MessageRole.USER -> "用户"
                MessageRole.ASSISTANT -> "助手"
                MessageRole.SYSTEM -> "已有摘要"
                else -> "对话"
            }
            "$speaker：${message.content}"
        }
    }

    private fun publishActive(
        conversationId: Long,
        thinking: StringBuilder,
        content: StringBuilder,
    ) {
        mutableGenerationState.value = GenerationState.Active(
            conversationId = conversationId,
            thinking = thinking.toString(),
            content = content.toString(),
        )
    }

    private fun publishFailure(conversationId: Long?, error: BackendError) {
        mutableGenerationState.value = GenerationState.Failed(
            eventId = nextEventId++,
            conversationId = conversationId,
            error = error,
        )
    }

    companion object {
        private const val MIN_MESSAGES_TO_COMPRESS = 6
    }
}

sealed class GenerationState {
    object Idle : GenerationState()

    data class Active(
        val conversationId: Long,
        val thinking: String,
        val content: String,
    ) : GenerationState()

    sealed class Terminal : GenerationState() {
        abstract val eventId: Long
        abstract val conversationId: Long?
    }

    data class Completed(
        override val eventId: Long,
        override val conversationId: Long,
        val message: BackendAssistantMessage,
    ) : Terminal()

    data class Failed(
        override val eventId: Long,
        override val conversationId: Long?,
        val error: BackendError,
    ) : Terminal()
}

/** 上下文压缩相关的一次性事件。 */
sealed class ContextEvent {
    data class CompressionStarted(val percent: Int) : ContextEvent()

    data class CompressionFinished(val percent: Int) : ContextEvent()

    data class CompressionFailed(val message: String) : ContextEvent()
}
