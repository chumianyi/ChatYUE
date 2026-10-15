package com.chatyue.chat

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
        val yesterdayStart = startOfDay(now, timeZone, -1)
        val last7DaysStart = startOfDay(now, timeZone, -7)
        val last30DaysStart = startOfDay(now, timeZone, -30)

        return when {
            updatedAt >= todayStart -> ConversationSection.TODAY
            updatedAt >= yesterdayStart -> ConversationSection.YESTERDAY
            updatedAt >= last7DaysStart -> ConversationSection.LAST_7_DAYS
            updatedAt >= last30DaysStart -> ConversationSection.LAST_30_DAYS
            else -> ConversationSection.EARLIER
        }
    }

    private fun startOfDay(
        timestamp: Long,
        timeZone: TimeZone,
        dayOffset: Int = 0,
    ): Long {
        if (dayOffset != 0) {
            val shifted = Calendar.getInstance(timeZone).apply {
                timeInMillis = timestamp
                add(Calendar.DAY_OF_YEAR, dayOffset)
            }.timeInMillis
            return startOfDay(shifted, timeZone)
        }
        return startOfDayInternal(timestamp, timeZone)
    }

    private fun startOfDayInternal(timestamp: Long, timeZone: TimeZone): Long {
        return Calendar.getInstance(timeZone).apply {
            timeInMillis = timestamp
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
