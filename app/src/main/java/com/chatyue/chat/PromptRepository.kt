package com.chatyue.chat

/** 一条快捷指令（提示词模板）。 */
data class Prompt(
    val id: Long,
    val title: String,
    val content: String,
    val createdAt: Long,
    val usageCount: Int = 0,
)

/** 快捷指令仓储。 */
interface PromptRepository {
    /** 取全部指令，按使用次数与创建时间倒序（越常用越靠前）。 */
    fun list(): List<Prompt>

    /** 新增指令，返回新 id。 */
    fun add(title: String, content: String): Long

    fun update(prompt: Prompt)

    fun delete(promptId: Long)

    /** 记录一次使用。 */
    fun incrementUsage(promptId: Long)

    /** 当前是否还没有任何指令（用于首次启动注入预置指令）。 */
    fun isEmpty(): Boolean
}
