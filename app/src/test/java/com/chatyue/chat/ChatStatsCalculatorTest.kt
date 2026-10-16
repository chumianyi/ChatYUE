package com.chatyue.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ChatStatsCalculatorTest {
    private val utc = TimeZone.getTimeZone("UTC")

    private fun message(id: Long, role: MessageRole, content: String, createdAt: Long) =
        ChatMessage(
            id = id,
            conversationId = 1L,
            role = role,
            content = content,
            createdAt = createdAt,
        )

    private fun timestamp(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        Calendar.getInstance(utc).apply {
            clear()
            set(year, month, day, hour, 0, 0)
        }.timeInMillis

    @Test
    fun calculate_countsMessagesAndCharacters() {
        val conversations = listOf(
            Conversation(id = 1L, title = "会话一", updatedAt = 0L),
            Conversation(id = 2L, title = "会话二", updatedAt = 0L),
        )
        val messages = listOf(
            message(1L, MessageRole.USER, "12345", 1000L),
            message(2L, MessageRole.ASSISTANT, "1234567890", 2000L),
            message(3L, MessageRole.THINKING_FINISHED, "不应计入", 2500L),
            message(4L, MessageRole.USER, "12", 3000L),
        )

        val stats = ChatStatsCalculator.calculate(conversations, messages)

        assertEquals(2, stats.conversationCount)
        assertEquals(3, stats.totalMessageCount)
        assertEquals(2, stats.userMessageCount)
        assertEquals(1, stats.assistantMessageCount)
        // 正文字符数按全部消息（含思考过程）累计：5 + 10 + 4 + 2。
        assertEquals(5 + 10 + 4 + 2, stats.totalCharacterCount)
        // 用户消息 5 + 2 = 7 字，平均 3。
        assertEquals(3, stats.averageUserCharacters)
        assertEquals(5, stats.longestUserCharacters)
        assertEquals(1000L, stats.earliestMessageAt)
    }

    @Test
    fun calculate_activeDaysAreDistinctCalendarDays() {
        val messages = listOf(
            message(1L, MessageRole.USER, "a", timestamp(2026, Calendar.JULY, 20, 1)),
            message(2L, MessageRole.USER, "b", timestamp(2026, Calendar.JULY, 20, 23)),
            message(3L, MessageRole.ASSISTANT, "c", timestamp(2026, Calendar.JULY, 21, 9)),
            message(4L, MessageRole.ASSISTANT, "d", timestamp(2026, Calendar.JULY, 25, 9)),
        )

        val stats = ChatStatsCalculator.calculate(emptyList(), messages)

        assertEquals(3, stats.activeDayCount)
    }

    @Test
    fun calculate_emptyInputStaysZero() {
        val stats = ChatStatsCalculator.calculate(emptyList(), emptyList())

        assertEquals(0, stats.totalMessageCount)
        assertEquals(0, stats.totalCharacterCount)
        assertEquals(0, stats.averageUserCharacters)
        assertEquals(0, stats.activeDayCount)
        assertNull(stats.earliestMessageAt)
    }

    @Test
    fun dailyCounts_returnsSevenConsecutiveDaysEndingToday() {
        val now = timestamp(2026, Calendar.JULY, 23, 15)

        val counts = ChatStatsCalculator.dailyCounts(emptyList(), now, utc)

        assertEquals(7, counts.size)
        // dayStart 是当天零点，断言也要用零点时间戳。
        assertEquals(timestamp(2026, Calendar.JULY, 17, 0), counts.first().dayStart)
        assertEquals(timestamp(2026, Calendar.JULY, 23, 0), counts.last().dayStart)
        counts.forEach { assertEquals(0, it.totalCount) }
    }

    @Test
    fun dailyCounts_bucketsMessagesByRoleAndDay() {
        val now = timestamp(2026, Calendar.JULY, 23, 15)
        val messages = listOf(
            message(1L, MessageRole.USER, "今天", timestamp(2026, Calendar.JULY, 23, 1)),
            message(2L, MessageRole.ASSISTANT, "今天回", timestamp(2026, Calendar.JULY, 22, 23)),
            message(3L, MessageRole.ASSISTANT, "更早", timestamp(2026, Calendar.JULY, 10, 8)),
            message(4L, MessageRole.THINKING_ACTIVE, "思考", timestamp(2026, Calendar.JULY, 23, 2)),
        )

        val counts = ChatStatsCalculator.dailyCounts(messages, now, utc)

        val today = counts.last()
        assertEquals(1, today.userCount)
        assertEquals(0, today.assistantCount)
        val yesterday = counts[counts.size - 2]
        assertEquals(0, yesterday.userCount)
        assertEquals(1, yesterday.assistantCount)
        // 超出 7 天窗口的消息不进入任何柱子。
        assertTrue(counts.sumOf { it.totalCount } == 2)
    }

    @Test
    fun dailyCounts_labelsAreChineseWeekdays() {
        val now = timestamp(2026, Calendar.JULY, 23, 15)

        val counts = ChatStatsCalculator.dailyCounts(emptyList(), now, utc)

        // 2026-07-23 是星期四，最后一天应是「周四」；前一天 07-22 是周三。
        assertEquals("周四", counts.last().label)
        assertEquals("周三", counts[counts.size - 2].label)
    }
}
