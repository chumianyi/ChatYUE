package com.chatyue.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ChatTimeDividerTest {
    private val utc = TimeZone.getTimeZone("UTC")

    private fun message(id: Long, content: String, createdAt: Long) =
        ChatMessage(
            id = id,
            conversationId = 1L,
            role = MessageRole.USER,
            content = content,
            createdAt = createdAt,
        )

    private fun timestamp(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        Calendar.getInstance(utc).apply {
            clear()
            set(year, month, day, hour, 0, 0)
        }.timeInMillis

    @Test
    fun emptyMessages_produceNoRows() {
        assertTrue(ChatTimeDivider.buildRows(emptyList(), timeZone = utc).isEmpty())
    }

    @Test
    fun singleMessage_getsTodayDivider() {
        val now = timestamp(2026, Calendar.OCTOBER, 16, 15)
        val rows = ChatTimeDivider.buildRows(
            listOf(message(1, "在吗", timestamp(2026, Calendar.OCTOBER, 16, 9))),
            now = now,
            timeZone = utc,
        )
        assertEquals(2, rows.size)
        val divider = rows[0] as ChatRow.Divider
        assertEquals("今天", divider.label)
        assertEquals(ChatRow.Message(message(1, "在吗", timestamp(2026, Calendar.OCTOBER, 16, 9))), rows[1])
    }

    @Test
    fun sameDayMessages_shareOneDivider() {
        val now = timestamp(2026, Calendar.OCTOBER, 16, 15)
        val rows = ChatTimeDivider.buildRows(
            listOf(
                message(1, "早上好", timestamp(2026, Calendar.OCTOBER, 16, 8)),
                message(2, "中午好", timestamp(2026, Calendar.OCTOBER, 16, 12)),
            ),
            now = now,
            timeZone = utc,
        )
        assertEquals(3, rows.size)
        assertEquals("今天", (rows[0] as ChatRow.Divider).label)
        assertTrue(rows[1] is ChatRow.Message)
        assertTrue(rows[2] is ChatRow.Message)
    }

    @Test
    fun yesterday_getsYesterdayDivider() {
        val now = timestamp(2026, Calendar.OCTOBER, 16, 0, 30)
        // now 取凌晨，确保「昨天」按自然日回退而不是 24 小时。
        val rows = ChatTimeDivider.buildRows(
            listOf(message(1, "昨晚", timestamp(2026, Calendar.OCTOBER, 15, 23))),
            now = now,
            timeZone = utc,
        )
        assertEquals("昨天", (rows[0] as ChatRow.Divider).label)
    }

    @Test
    fun olderSameYear_usesMonthDay() {
        val now = timestamp(2026, Calendar.OCTOBER, 16, 12)
        val rows = ChatTimeDivider.buildRows(
            listOf(message(1, "国庆后", timestamp(2026, Calendar.OCTOBER, 8, 10))),
            now = now,
            timeZone = utc,
        )
        assertEquals("10月8日", (rows[0] as ChatRow.Divider).label)
    }

    @Test
    fun olderDifferentYear_usesFullDate() {
        val now = timestamp(2026, Calendar.OCTOBER, 16, 12)
        val rows = ChatTimeDivider.buildRows(
            listOf(message(1, "去年", timestamp(2025, Calendar.DECEMBER, 31, 23))),
            now = now,
            timeZone = utc,
        )
        assertEquals("2025年12月31日", (rows[0] as ChatRow.Divider).label)
    }

    @Test
    fun dividerId_isStableAndNegative() {
        val now = timestamp(2026, Calendar.OCTOBER, 16, 12)
        val rows = ChatTimeDivider.buildRows(
            listOf(
                message(1, "前天", timestamp(2026, Calendar.OCTOBER, 14, 9)),
                message(2, "今天", timestamp(2026, Calendar.OCTOBER, 16, 9)),
            ),
            now = now,
            timeZone = utc,
        )
        val first = rows[0] as ChatRow.Divider
        val second = rows[2] as ChatRow.Divider
        assertTrue(first.id < 0)
        assertTrue(second.id < 0)
        // 不同日期的分隔条 id 必须不同，RecyclerView 稳定 id 才不会串行。
        assertTrue(first.id != second.id)
        // 同一天的消息再次构建，分隔条 id 不变。
        val rebuilt = ChatTimeDivider.buildRows(
            listOf(message(2, "今天", timestamp(2026, Calendar.OCTOBER, 16, 9))),
            now = now,
            timeZone = utc,
        )
        assertEquals(second.id, (rebuilt[0] as ChatRow.Divider).id)
    }

    @Test
    fun dividesAcrossDaysInOrder() {
        val now = timestamp(2026, Calendar.OCTOBER, 16, 12)
        val rows = ChatTimeDivider.buildRows(
            listOf(
                message(1, "前天", timestamp(2026, Calendar.OCTOBER, 14, 9)),
                message(2, "昨晚", timestamp(2026, Calendar.OCTOBER, 15, 22)),
                message(3, "今早", timestamp(2026, Calendar.OCTOBER, 16, 8)),
                message(4, "此刻", timestamp(2026, Calendar.OCTOBER, 16, 11)),
            ),
            now = now,
            timeZone = utc,
        )
        val labels = rows.filterIsInstance<ChatRow.Divider>().map { it.label }
        assertEquals(listOf("10月14日", "昨天", "今天"), labels)
        assertEquals(7, rows.size)
    }

    @Test
    fun streamingPlaceholders_groupIntoToday() {
        // 流式输出占位消息用 Long.MAX_VALUE 量级的时间戳，应归入「今天」分组。
        val now = timestamp(2026, Calendar.OCTOBER, 16, 12)
        val rows = ChatTimeDivider.buildRows(
            listOf(
                message(1, "在吗", timestamp(2026, Calendar.OCTOBER, 16, 9)),
                ChatMessage(
                    id = -1L,
                    conversationId = 1L,
                    role = MessageRole.THINKING_ACTIVE,
                    content = "思考中",
                    createdAt = Long.MAX_VALUE - 1,
                ),
                ChatMessage(
                    id = -2L,
                    conversationId = 1L,
                    role = MessageRole.ASSISTANT,
                    content = "回答",
                    createdAt = Long.MAX_VALUE,
                ),
            ),
            now = now,
            timeZone = utc,
        )
        val labels = rows.filterIsInstance<ChatRow.Divider>().map { it.label }
        assertEquals(listOf("今天"), labels)
        assertEquals(4, rows.size)
    }
}
