package com.chatyue.chat

import java.util.Calendar

/**
 * 消息时间显示：把时间戳转成聊天里常见的友好文案。
 *
 * - 今天：HH:mm
 * - 昨天：昨天 HH:mm
 * - 今年内：M月d日 HH:mm
 * - 跨年：yyyy年M月d日 HH:mm
 *
 * `nowMillis` 作为参数注入，方便单元测试固定「当前时间」，
 * 函数本身对同样的 (then, now) 永远返回相同结果。
 */
object MessageTimeFormatter {

    fun format(thenMillis: Long, nowMillis: Long): String {
        if (thenMillis <= 0) return ""
        val then = Calendar.getInstance().apply { timeInMillis = thenMillis }
        val now = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val time = "%02d:%02d".format(then.get(Calendar.HOUR_OF_DAY), then.get(Calendar.MINUTE))

        val sameDay = isSameDay(then, now)
        if (sameDay) return time

        val yesterday = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            add(Calendar.DAY_OF_YEAR, -1)
        }
        if (isSameDay(then, yesterday)) return "昨天 $time"

        return if (then.get(Calendar.YEAR) == now.get(Calendar.YEAR)) {
            "${then.get(Calendar.MONTH) + 1}月${then.get(Calendar.DAY_OF_MONTH)}日 $time"
        } else {
            "${then.get(Calendar.YEAR)}年${then.get(Calendar.MONTH) + 1}月${then.get(Calendar.DAY_OF_MONTH)}日 $time"
        }
    }

    private fun isSameDay(a: Calendar, b: Calendar): Boolean =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
}
