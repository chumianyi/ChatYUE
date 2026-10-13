package com.deepseekold.chat.backend

/**
 * 一次聊天请求。
 *
 * 注意：模型、接口地址、密钥均为全局固定值（见 [FixedEndpoint]），
 * 且应用只有"思考模式"一种模式，因此这里不再暴露任何可切换参数。
 */
data class ChatRequest(
    val conversationId: String?,
    val messages: List<BackendMessage>,
)

data class BackendMessage(
    val role: BackendRole,
    val content: String,
)

enum class BackendRole {
    SYSTEM,
    USER,
    ASSISTANT,
}

data class BackendAssistantMessage(
    val content: String,
    val thinking: String? = null,
    val finishReason: BackendFinishReason = BackendFinishReason.STOP,
)

enum class BackendFinishReason {
    STOP,
    LENGTH,
    CONTENT_FILTER,
}

data class BackendError(
    val message: String,
    val code: String? = null,
    val type: BackendErrorType = BackendErrorType.PROTOCOL,
    val retryable: Boolean = false,
)

enum class BackendErrorType {
    MISSING_API_KEY,
    AUTHENTICATION,
    RATE_LIMIT,
    SERVER,
    NETWORK,
    CANCELED,
    PROTOCOL,
}
