package com.chatyue.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBudgetTest {

    @Test
    fun chineseCountsOneAndAHalfTokenPerChar() {
        assertEquals(1.5, ContextBudget.tokensForChar('中'), 0.000001)
        assertEquals(3.0, ContextBudget.estimateTokens("中文"), 0.000001)
    }

    @Test
    fun englishCountsQuarterTokenPerChar() {
        assertEquals(0.25, ContextBudget.tokensForChar('a'), 0.000001)
        assertEquals(1.0, ContextBudget.estimateTokens("abcd"), 0.000001)
    }

    @Test
    fun mixedTextSumsBothRules() {
        // 2 个中文（3.0）+ 4 个英文（1.0）
        assertEquals(4.0, ContextBudget.estimateTokens("中文abcd"), 0.000001)
    }

    @Test
    fun limitIsOneMillion() {
        assertEquals(1024.0 * 1024.0, ContextBudget.MAX_TOKENS, 0.000001)
        assertEquals(85, ContextBudget.COMPRESS_THRESHOLD_PERCENT)
    }

    @Test
    fun percentIsRoundedToWholeNumber() {
        // 594193 个汉字 = 891289.5 token，约占 1M 的 85.00%
        val message = messageOf(CJK_CHARS_FOR_85_PERCENT)
        assertEquals(85, ContextBudget.percentOf(listOf(message)))
    }

    @Test
    fun compressionTriggersAtEightyFivePercent() {
        val below = messageOf(CJK_CHARS_BELOW_THRESHOLD)
        val reached = messageOf(CJK_CHARS_FOR_85_PERCENT)
        assertFalse(ContextBudget.needsCompression(listOf(below)))
        assertTrue(ContextBudget.needsCompression(listOf(reached)))
    }

    @Test
    fun thinkingMessagesAreNotPartOfContext() {
        val thinking = ChatMessage(
            id = 1,
            conversationId = 1,
            role = MessageRole.THINKING_FINISHED,
            content = "思考",
            createdAt = 1,
        )
        val summary = thinking.copy(id = 2, role = MessageRole.SYSTEM)
        val user = thinking.copy(id = 3, role = MessageRole.USER)

        assertFalse(thinking.role.isContextMessage)
        assertTrue(summary.role.isContextMessage)
        assertTrue(user.role.isContextMessage)
    }

    private fun messageOf(cjkCount: Int): ChatMessage {
        return ChatMessage(
            id = 1,
            conversationId = 1,
            role = MessageRole.ASSISTANT,
            content = "一".repeat(cjkCount),
            createdAt = 1,
        )
    }

    private companion object {
        /** 594193 个汉字 ≈ 891289.5 token ≈ 1M（1048576）的 85%。 */
        private const val CJK_CHARS_FOR_85_PERCENT = 594193

        /** 589193 个汉字 ≈ 883789.5 token ≈ 1M 的 84.3%，明确低于压缩阈值。 */
        private const val CJK_CHARS_BELOW_THRESHOLD = 589193
    }
}
