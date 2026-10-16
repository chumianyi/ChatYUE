package com.chatyue.chat

import java.util.Calendar
import java.util.TimeZone

/** 聊天统计汇总结果。 */
data class ChatStats(
    val conversationCount: Int,
    val userMessageCount: Int,
    val assistantMessageCount: Int,
    val activeDayCount: Int,
    val totalCharacterCount: Int,
    val averageUserCharacters: Int,
    val longestUserCharacters: Int,
    val earliestMessageAt: Long?,
) {
    val totalMessageCount: Int get() = userMessageCount + assistantMessageCount
}

/** 近 7 天柱状图的一天数据。 */
data class DailyCount(
    val dayStart: Long,
    val label: String,
    val userCount: Int,
    val assistantCount: Int,
) {
    val totalCount: Int get() = userCount + assistantCount
}

/**
 * 聊天统计计算：把全部会话消息榨成摘要数字与近 7 天分布。
 *
 * 纯函数实现，不碰数据库，方便单元测试。
 */
object ChatStatsCalculator {
    private const val WEEK_DAYS = 7

    fun calculate(conversations: List<Conversation>, messages: List<ChatMessage>): ChatStats {
        val userMessages = messages.filter { it.role == MessageRole.USER }
        val assistantMessages = messages.filter { it.role == MessageRole.ASSISTANT }
        val userCharacterCounts = userMessages.map { it.content.length }
        return ChatStats(
            conversationCount = conversations.size,
            userMessageCount = userMessages.size,
            assistantMessageCount = assistantMessages.size,
            activeDayCount = countActiveDays(messages),
            totalCharacterCount = messages.sumOf { it.content.length },
            averageUserCharacters = if (userCharacterCounts.isEmpty()) {
                0
            } else {
                userCharacterCounts.average().toInt()
            },
            longestUserCharacters = userCharacterCounts.maxOrNull() ?: 0,
            earliestMessageAt = messages.minOfOrNull { it.createdAt },
        )
    }

    /**
     * 近 [WEEK_DAYS] 天（含今天）的每日消息数，按日期正序返回。
     *
     * 没有消息的日期也会占位，保证柱子连续；标签为「周几」。
     * 分桶按自然日边界匹配，夏令时地区也不会串天。
     */
    fun dailyCounts(
        messages: List<ChatMessage>,
        now: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): List<DailyCount> {
        val todayStart = startOfDay(now, timeZone)
        val dayStarts = List(WEEK_DAYS) { index ->
            val offset = index - (WEEK_DAYS - 1)
            Calendar.getInstance(timeZone).apply {
                timeInMillis = todayStart
                add(Calendar.DAY_OF_YEAR, offset)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }
        val buckets = Array(WEEK_DAYS) { intArrayOf(0, 0) }
        messages.forEach { message ->
            val dayStart = startOfDay(message.createdAt, timeZone)
            val index = dayStarts.indexOf(dayStart)
            if (index < 0) return@forEach
            when (message.role) {
                MessageRole.USER -> buckets[index][0]++
                MessageRole.ASSISTANT -> buckets[index][1]++
                else -> Unit
            }
        }
        return List(WEEK_DAYS) { index ->
            DailyCount(
                dayStart = dayStarts[index],
                label = WEEKDAY_LABELS[weekdayOf(dayStarts[index], timeZone)],
                userCount = buckets[index][0],
                assistantCount = buckets[index][1],
            )
        }
    }

    private fun countActiveDays(messages: List<ChatMessage>): Int {
        val timeZone = TimeZone.getDefault()
        return messages
            .map { startOfDay(it.createdAt, timeZone) }
            .distinct()
            .size
    }

    private fun startOfDay(timestamp: Long, timeZone: TimeZone): Long {
        val calendar = Calendar.getInstance(timeZone).apply {
            timeInMillis = timestamp
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }

    private fun weekdayOf(timestamp: Long, timeZone: TimeZone): Int {
        val calendar = Calendar.getInstance(timeZone).apply { timeInMillis = timestamp }
        // Calendar.SUNDAY = 1 … SATURDAY = 7，映射到周日起始的标签数组。
        return (calendar.get(Calendar.DAY_OF_WEEK) + 5) % WEEK_DAYS
    }

    private val WEEKDAY_LABELS = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")}
