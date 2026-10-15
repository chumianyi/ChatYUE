package com.chatyue.chat.tools

import com.chatyue.chat.backend.BackendAssistantMessage
import com.chatyue.chat.backend.BackendError
import com.chatyue.chat.backend.BackendMessage
import com.chatyue.chat.backend.CancellableRequest
import com.chatyue.chat.backend.ChatBackend
import com.chatyue.chat.backend.ChatRequest
import com.chatyue.chat.backend.DeepSeekChatBackend

/**
 * 侧边栏工具的流式请求入口。
 *
 * 接口地址、密钥与模型同样固定走 [com.chatyue.chat.backend.FixedEndpoint]，
 * 工具页面不需要（也无法）选择模型或填写密钥。
 */
object ToolApi {
    private val backend: ChatBackend by lazy { DeepSeekChatBackend() }

    fun chat(
        messages: List<BackendMessage>,
        onTextDelta: (String) -> Unit,
        onThinkingDelta: (String) -> Unit = {},
        onCompleted: (String) -> Unit = {},
        onFailure: (String) -> Unit = {},
    ): CancellableRequest {
        return backend.sendMessage(
            ChatRequest(conversationId = null, messages = messages),
            object : ChatBackend.Callback {
                override fun onThinkingDelta(text: String) {
                    onThinkingDelta(text)
                }

                override fun onTextDelta(text: String) {
                    onTextDelta(text)
                }

                override fun onCompleted(message: BackendAssistantMessage) {
                    onCompleted(message.content)
                }

                override fun onFailure(error: BackendError) {
                    onFailure(error.message.ifBlank { "请求失败，请稍后重试。" })
                }
            },
        )
    }
}
