package com.chatyue.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class MessageTimeFormatterTest {

    private fun at(hour: Int, minute: Int, dayOffsetFromNow: Int = 0, yearShift: Int = 0): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = System.currentTimeMillis()
            if (yearShift != 0) add(Calendar.YEAR, yearShift)
            if (dayOffsetFromNow != 0) add(Calendar.DAY_OF_YEAR, dayOffsetFromNow)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    private fun now() = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 12)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun `今天的只显示时分`() {
        val then = at(9, 5)
        assertEquals("09:05", MessageTimeFormatter.format(then, now()))
    }

    @Test
    fun `昨天的带昨天前缀`() {
        val then = at(23, 30, dayOffsetFromNow = -1)
        assertEquals("昨天 23:30", MessageTimeFormatter.format(then, now()))
    }

    @Test
    fun `今年的显示月日时分`() {
        val then = at(8, 0, dayOffsetFromNow = -5)
        val result = MessageTimeFormatter.format(then, now())
        // 不写死具体月日，只校验整体格式为「M月d日 HH:mm」。
        assertTrue(result.matches(Regex("^\\d+月\\d+日 \\d{2}:\\d{2}$")))
    }

    @Test
    fun `跨年的显示完整日期`() {
        val then = at(8, 0, dayOffsetFromNow = -5, yearShift = -1)
        val result = MessageTimeFormatter.format(then, now())
        assertTrue(result.endsWith(" 08:00"))
        assertTrue(result.contains("年"))
        assertTrue(result.contains("月"))
    }

    @Test
    fun `零或负时间戳返回空串`() {
        assertEquals("", MessageTimeFormatter.format(0, now()))
        assertEquals("", MessageTimeFormatter.format(-1, now()))
    }
}
