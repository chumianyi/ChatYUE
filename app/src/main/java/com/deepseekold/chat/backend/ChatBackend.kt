package com.deepseekold.chat.backend

interface ChatBackend {
    fun sendMessage(
        request: ChatRequest,
        callback: Callback,
    ): CancellableRequest

    interface Callback {
        fun onThinkingDelta(text: String)

        fun onTextDelta(text: String)

        fun onCompleted(message: BackendAssistantMessage)

        fun onFailure(error: BackendError)
    }
}

fun interface CancellableRequest {
    fun cancel()
}
