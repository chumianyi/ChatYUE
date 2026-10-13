package com.deepseekold.chat

import java.util.Calendar
import java.util.TimeZone

object ConversationFormatter {
    private const val MAX_TITLE_LENGTH = 20

    fun titleFrom(message: String): String {
        return message
            .trim()
            .replace(Regex("\\s+"), " ")
            .take(MAX_TITLE_LENGTH)
    }

    fun sectionFor(
        updatedAt: Long,
        now: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): ConversationSection {
        val todayStart = startOfDay(now, timeZone)
        val calendar = Calendar.getInstance(timeZone).apply {
            timeInMillis = todayStart
            add(Calendar.DAY_OF_YEAR, -1)
        }
        val yesterdayStart = calendar.timeInMillis

        return when {
            updatedAt >= todayStart -> ConversationSection.TODAY
            updatedAt >= yesterdayStart -> ConversationSection.YESTERDAY
            else -> ConversationSection.EARLIER
        }
    }

    private fun startOfDay(timestamp: Long, timeZone: TimeZone): Long {
        return Calendar.getInstance(timeZone).apply {
            timeInMillis = timestamp
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
