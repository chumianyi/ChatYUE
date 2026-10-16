package com.chatyue.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 内存版指令仓储，供预置指令逻辑单测使用。 */
private class FakePromptRepository : PromptRepository {
    private val items = mutableListOf<Prompt>()
    private var nextId = 1L

    override fun list(): List<Prompt> =
        items.sortedWith(compareByDescending<Prompt> { it.usageCount }.thenByDescending { it.createdAt })

    override fun add(title: String, content: String): Long {
        val id = nextId++
        items.add(Prompt(id = id, title = title, content = content, createdAt = id))
        return id
    }

    override fun update(prompt: Prompt) {
        val index = items.indexOfFirst { it.id == prompt.id }
        if (index >= 0) items[index] = prompt
    }

    override fun delete(promptId: Long) {
        items.removeAll { it.id == promptId }
    }

    override fun incrementUsage(promptId: Long) {
        val index = items.indexOfFirst { it.id == promptId }
        if (index >= 0) {
            items[index] = items[index].copy(usageCount = items[index].usageCount + 1)
        }
    }

    override fun isEmpty(): Boolean = items.isEmpty()
}

class PresetPromptsTest {
    @Test
    fun presets_areAllValid() {
        val titles = mutableSetOf<String>()
        PresetPrompts.all.forEach { preset ->
            assertTrue("预置指令标题不能为空: $preset", PromptFormatter.isValidTitle(preset.title))
            assertTrue("预置指令正文不能为空: $preset", PromptFormatter.isValidContent(preset.content))
            assertTrue("预置指令标题不能重复: ${preset.title}", titles.add(preset.title))
        }
    }

    @Test
    fun presets_endWithNewlineSoUserCanAppendContent() {
        // 正文以换行结尾，用户点进来后直接接着写待处理内容即可。
        PresetPrompts.all.forEach { preset ->
            assertTrue("正文应以换行结尾: ${preset.title}", preset.content.endsWith("\n"))
        }
    }

    @Test
    fun ensureSeeded_fillsEmptyRepositoryOnce() {
        val repository = FakePromptRepository()
        assertTrue(PresetPrompts.ensureSeeded(repository))
        assertEquals(PresetPrompts.all.size, repository.list().size)
        // 第二次调用不再重复注入。
        assertFalse(PresetPrompts.ensureSeeded(repository))
        assertEquals(PresetPrompts.all.size, repository.list().size)
    }

    @Test
    fun ensureSeeded_keepsExistingPrompts() {
        val repository = FakePromptRepository()
        repository.add("我自己的指令", "帮我做点事：\n")
        assertFalse(PresetPrompts.ensureSeeded(repository))
        val titles = repository.list().map { it.title }
        assertEquals(listOf("我自己的指令"), titles)
    }

    @Test
    fun usageCount_affectsOrdering() {
        val repository = FakePromptRepository()
        val first = repository.add("指令甲", "内容甲\n")
        val second = repository.add("指令乙", "内容乙\n")
        repository.incrementUsage(second)
        val ordered = repository.list().map { it.title }
        assertEquals(listOf("指令乙", "指令甲"), ordered)
        repository.incrementUsage(first)
        repository.incrementUsage(first)
        assertEquals(listOf("指令甲", "指令乙"), repository.list().map { it.title })
    }
}
