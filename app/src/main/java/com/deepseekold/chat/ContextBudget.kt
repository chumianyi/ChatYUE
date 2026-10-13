package com.deepseekold.chat

import kotlin.math.roundToInt

/**
 * 上下文用量估算。
 *
 * 估算规则（按字符计）：
 * - 中文（含中日韩统一表意文字与全角标点）：每字符 1.5 token
 * - 英文及其他字符：每字符 0.25 token
 *
 * 上限固定为 128K，达到 [COMPRESS_THRESHOLD_PERCENT] 时触发自动压缩。
 */
object ContextBudget {
    /** 上下文上限：128K。 */
    const val MAX_TOKENS: Double = 128.0 * 1024.0

    /** 触发自动压缩的百分比阈值。 */
    const val COMPRESS_THRESHOLD_PERCENT: Int = 85

    private const val CJK_TOKENS_PER_CHAR = 1.5
    private const val OTHER_TOKENS_PER_CHAR = 0.25

    /** 单个字符的估算 token 数。 */
    fun tokensForChar(char: Char): Double {
        return if (isCjk(char)) CJK_TOKENS_PER_CHAR else OTHER_TOKENS_PER_CHAR
    }

    /** 估算一段文本的 token 数。 */
    fun estimateTokens(text: String): Double {
        var total = 0.0
        for (char in text) {
            total += tokensForChar(char)
        }
        return total
    }

    /** 估算一组消息占用的 token 总数。 */
    fun estimateTokens(messages: List<ChatMessage>): Double {
        var total = 0.0
        for (message in messages) {
            total += estimateTokens(message.content)
        }
        return total
    }

    /** 已使用的百分比（0~100，向上取整，避免 0.4% 显示为 0%）。 */
    fun percentOf(tokens: Double): Int {
        if (tokens <= 0.0) return 0
        return ((tokens / MAX_TOKENS) * 100.0).roundToInt().coerceIn(0, 100)
    }

    /** 一组消息占用的百分比。 */
    fun percentOf(messages: List<ChatMessage>): Int = percentOf(estimateTokens(messages))

    /** 是否已达到需要自动压缩的阈值。 */
    fun needsCompression(messages: List<ChatMessage>): Boolean {
        return percentOf(messages) >= COMPRESS_THRESHOLD_PERCENT
    }

    /** 判断字符是否按中文（1.5 token/字）计算。 */
    fun isCjk(char: Char): Boolean {
        val code = char.code
        return code in 0x3400..0x4DBF || // 扩展 A
            code in 0x4E00..0x9FFF || // 基本区
            code in 0xF900..0xFAFF || // 兼容表意文字
            code in 0x3000..0x303F || // 中日韩符号与标点
            code in 0xFF00..0xFFEF // 全角字符
    }
}
