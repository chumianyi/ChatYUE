package com.chatyue.chat

import java.util.Calendar
import java.util.TimeZone

/**
 * 聊天列表的一行数据：消息或按自然日分组的时间分隔条。
 *
 * 消息行的 id 直接复用消息 id；分隔条 id 取「当天起始时刻的相反数」，
 * 与消息的正 id 不冲突，且同一天的分隔条跨刷新保持稳定。
 */
sealed class ChatRow {
    data class Message(val message: ChatMessage) : ChatRow()

    data class Divider(val id: Long, val label: String) : ChatRow()
}

/**
 * 把按时间正序的消息列表切成带日期分隔条的行列表（纯函数，方便单测）。
 *
 * 分隔规则与微信等常见聊天一致：同一天的第一条消息前插一条分隔条；
 * 标签依次为「今天 / 昨天 / M月d日 / YYYY年M月d日」。
 * 分桶按自然日边界计算，夏令时地区也不会串天。
 */
object ChatTimeDivider {

    fun buildRows(
        messages: List<ChatMessage>,
        now: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): List<ChatRow> {
        if (messages.isEmpty()) return emptyList()
        val todayStart = startOfDay(now, timeZone)
        val yesterdayStart = dayBefore(todayStart, timeZone)
        val rows = mutableListOf<ChatRow>()
        var lastDayStart = Long.MIN_VALUE
        messages.forEach { message ->
            // 流式输出的占位消息时间戳远在未来，按「今天」归组，避免出现未来日期的分隔条。
            val effectiveTime = message.createdAt.coerceAtMost(now)
            val dayStart = startOfDay(effectiveTime, timeZone)
            if (dayStart != lastDayStart) {
                lastDayStart = dayStart
                rows.add(
                    ChatRow.Divider(
                        id = -dayStart,
                        label = labelFor(dayStart, todayStart, yesterdayStart, timeZone),
                    ),
                )
            }
            rows.add(ChatRow.Message(message))
        }
        return rows
    }

    private fun labelFor(
        dayStart: Long,
        todayStart: Long,
        yesterdayStart: Long,
        timeZone: TimeZone,
    ): String = when (dayStart) {
        todayStart -> "今天"
        yesterdayStart -> "昨天"
        else -> {
            val calendar = Calendar.getInstance(timeZone).apply { timeInMillis = dayStart }
            val year = calendar.get(Calendar.YEAR)
            val nowYear = Calendar.getInstance(timeZone)
                .apply { timeInMillis = todayStart }
                .get(Calendar.YEAR)
            val month = calendar.get(Calendar.MONTH) + 1
            val day = calendar.get(Calendar.DAY_OF_MONTH)
            if (year == nowYear) "${month}月${day}日" else "${year}年${month}月${day}日"
        }
    }

    private fun dayBefore(dayStart: Long, timeZone: TimeZone): Long =
        Calendar.getInstance(timeZone).apply {
            timeInMillis = dayStart
            add(Calendar.DAY_OF_YEAR, -1)
        }.timeInMillis

    private fun startOfDay(timestamp: Long, timeZone: TimeZone): Long =
        Calendar.getInstance(timeZone).apply {
            timeInMillis = timestamp
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
}
