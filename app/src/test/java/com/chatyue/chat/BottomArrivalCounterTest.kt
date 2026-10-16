package com.chatyue.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomArrivalCounterTest {
    @Test
    fun firstRenderAtBottom_countsNothing() {
        val counter = BottomArrivalCounter()
        assertFalse(counter.onRender(tailId = 7L, atBottom = true))
        assertEquals(0, counter.unreadCount)
    }

    @Test
    fun newMessageWhileAway_countsOnce() {
        val counter = BottomArrivalCounter()
        counter.onRender(tailId = 7L, atBottom = true)
        assertTrue(counter.onRender(tailId = 8L, atBottom = false))
        assertEquals(1, counter.unreadCount)
    }

    @Test
    fun streamingRefreshesWithSameTailId_doNotDoubleCount() {
        val counter = BottomArrivalCounter()
        // 流式输出：占位消息 id 不变，反复刷新不应重复计数。
        counter.onRender(tailId = 8L, atBottom = false)
        assertFalse(counter.onRender(tailId = 8L, atBottom = false))
        assertFalse(counter.onRender(tailId = 8L, atBottom = false))
        assertEquals(1, counter.unreadCount)
    }

    @Test
    fun multipleMessagesWhileAway_accumulate() {
        val counter = BottomArrivalCounter()
        counter.onRender(tailId = 1L, atBottom = false)
        counter.onRender(tailId = 2L, atBottom = false)
        counter.onRender(tailId = 3L, atBottom = false)
        assertEquals(3, counter.unreadCount)
    }

    @Test
    fun returningToBottom_clearsCount() {
        val counter = BottomArrivalCounter()
        counter.onRender(tailId = 1L, atBottom = false)
        counter.onRender(tailId = 2L, atBottom = false)
        assertEquals(2, counter.unreadCount)
        counter.clearOnReturnToBottom()
        assertEquals(0, counter.unreadCount)
    }

    @Test
    fun renderingWhileAtBottom_clearsCount() {
        val counter = BottomArrivalCounter()
        counter.onRender(tailId = 1L, atBottom = false)
        counter.onRender(tailId = 2L, atBottom = false)
        assertEquals(2, counter.unreadCount)
        // 用户回到顶部？不——是列表在底部刷新，计数应清零。
        counter.onRender(tailId = 3L, atBottom = true)
        assertEquals(0, counter.unreadCount)
    }

    @Test
    fun clearKeepsTailId_soSameMessageIsNotRecounted() {
        val counter = BottomArrivalCounter()
        counter.onRender(tailId = 42L, atBottom = false)
        counter.clearOnReturnToBottom()
        // 回到底部后列表会再渲染一次同样的尾部，不应计为新消息。
        assertFalse(counter.onRender(tailId = 42L, atBottom = false))
        assertEquals(0, counter.unreadCount)
    }

    @Test
    fun emptyList_resetsCount() {
        val counter = BottomArrivalCounter()
        counter.onRender(tailId = 9L, atBottom = false)
        counter.onRender(tailId = null, atBottom = false)
        assertEquals(0, counter.unreadCount)
        // 空列表之后的第一条新消息重新从 1 计起。
        assertTrue(counter.onRender(tailId = 10L, atBottom = false))
        assertEquals(1, counter.unreadCount)
    }

    @Test
    fun reset_forgetsTailId() {
        val counter = BottomArrivalCounter()
        counter.onRender(tailId = 5L, atBottom = true)
        counter.reset()
        // 切换会话后同 id 也视为新到达。
        assertTrue(counter.onRender(tailId = 5L, atBottom = false))
        assertEquals(1, counter.unreadCount)
    }
}
