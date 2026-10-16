package com.chatyue.chat

/**
 * 应用内使用的系统提示词。
 */
object Prompts {
    /** 上下文压缩时使用的系统提示词。 */
    const val COMPRESSION_SYSTEM: String =
        "你是一个对话摘要引擎。用户会给你一段较早的对话记录，" +
            "请把它压缩成一条精简的摘要，要求：\n" +
            "1. 完整保留关键事实、人物、数字、结论、用户偏好与尚未完成的待办；\n" +
            "2. 不要添加任何推测、评价或新信息；\n" +
            "3. 使用第三人称叙述，不要写成问答；\n" +
            "4. 只输出摘要正文，不要输出标题、前缀或任何解释。"

    /** 上下文压缩时携带的对话原文。 */
    fun compressionUserPrompt(transcript: String): String =
        "以下是需要压缩的对话记录：\n\n$transcript\n\n请输出压缩后的摘要。"

    /**
     * 2.0 全局记忆：把其他全局会话的历史作为 system 消息注入，
     * 让模型可以调用用户在所有全局对话中沉淀的信息。
     */
    fun globalMemorySystem(transcript: String): String =
        "以下是用户在其他历史全局会话中的对话记录，属于用户的长期记忆。" +
            "当前对话中如果遇到与这些记录相关的信息，可以直接参考并保持一致性；" +
            "若与当前对话无关，则忽略即可，不要主动向用户提起：\n\n$transcript"
}

/** 参与上下文计算、并会被发送给模型的消息角色。 */
val MessageRole.isContextMessage: Boolean
    get() = this == MessageRole.USER ||
        this == MessageRole.ASSISTANT ||
        this == MessageRole.SYSTEM
