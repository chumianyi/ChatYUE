package com.deepseekold.chat

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ConversationFormatterTest {
    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun titleFrom_compactsWhitespaceAndLimitsLength() {
        val title = ConversationFormatter.titleFrom(
            "  这是一个   很长的会话标题，用来验证标题自动生成规则是否正确  ",
        )

        assertEquals("这是一个 很长的会话标题，用来验证标题自", title)
    }

    @Test
    fun sectionFor_groupsTodayYesterdayAndEarlier() {
        val now = timestamp(2026, Calendar.JULY, 21, 14)

        assertEquals(
            ConversationSection.TODAY,
            ConversationFormatter.sectionFor(
                timestamp(2026, Calendar.JULY, 21, 1),
                now,
                utc,
            ),
        )
        assertEquals(
            ConversationSection.YESTERDAY,
            ConversationFormatter.sectionFor(
                timestamp(2026, Calendar.JULY, 20, 23),
                now,
                utc,
            ),
        )
        assertEquals(
            ConversationSection.EARLIER,
            ConversationFormatter.sectionFor(
                timestamp(2026, Calendar.JULY, 19, 23),
                now,
                utc,
            ),
        )
    }

    private fun timestamp(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
    ): Long {
        return Calendar.getInstance(utc).apply {
            clear()
            set(year, month, day, hour, 0, 0)
        }.timeInMillis
    }
}
