package com.chatyue.chat

/**
 * 首次启动注入的预置快捷指令。
 *
 * 全部是聊天场景里真实可用的指令模板：正文里对「待处理内容」的位置统一留了
 * 换行结尾，用户从指令库点进来后直接把要处理的内容接在后面即可。
 */
object PresetPrompts {

    /** 一条预置指令的定义。 */
    data class Preset(
        val title: String,
        val content: String,
    )

    val all: List<Preset> = listOf(
        Preset(
            title = "中英互译",
            content = "请把下面的内容翻译成英文，保留原有格式与专业术语，" +
                "译文要通顺自然，不要漏译也不要自行发挥：\n",
        ),
        Preset(
            title = "总结要点",
            content = "请用不超过五句话总结下面这段内容，先给一句结论，" +
                "再分条列出关键要点：\n",
        ),
        Preset(
            title = "解释代码",
            content = "请逐段解释下面这段代码在做什么，指出其中的关键逻辑、" +
                "边界条件与潜在问题，必要时给出改写建议：\n",
        ),
        Preset(
            title = "头脑风暴",
            content = "请围绕下面的主题头脑风暴十个有差异化的创意，" +
                "每个创意附一句可行性说明：\n",
        ),
        Preset(
            title = "润色邮件",
            content = "请把下面的内容改写成一封措辞得体、结构清晰的邮件，" +
                "保持原意不变，语气礼貌专业：\n",
        ),
        Preset(
            title = "整理笔记",
            content = "请把下面这段材料整理成结构化笔记，包含核心观点、" +
                "关键论据和一份可执行的行动清单：\n",
        ),
        Preset(
            title = "起标题",
            content = "请围绕下面的内容给出十个不同风格的标题，" +
                "覆盖正式、生动、悬念三类风格，并各附一句理由：\n",
        ),
        Preset(
            title = "检查错别字",
            content = "请检查下面这段文字中的错别字、标点误用和语病，" +
                "用表格列出「原文 / 建议 / 原因」三列，不要改写其他内容：\n",
        ),
    )

    /**
     * 若指令库还是空的，就把预置指令灌进去（首次启动只做一次）。
     * 返回 true 表示本次执行了注入。
     */
    fun ensureSeeded(repository: PromptRepository): Boolean {
        if (!repository.isEmpty()) return false
        all.forEach { repository.add(it.title, it.content) }
        return true
    }
}
