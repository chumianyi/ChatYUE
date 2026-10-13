package com.deepseekold.chat.tools

import com.deepseekold.chat.backend.BackendMessage
import com.deepseekold.chat.backend.BackendRole

/**
 * 四个侧边栏工具的提示词与协议约定。
 */
object ToolPrompts {

    // ---------------- 智能翻译 ----------------

    /** 翻译方向：5 组互译，每组两个方向。 */
    data class TranslationPair(val left: String, val right: String) {
        fun label(): String = "$left ↔ $right"

        fun direction(fromLeftToRight: Boolean): String =
            if (fromLeftToRight) "$left → $right" else "$right → $left"
    }

    val TRANSLATION_PAIRS: List<TranslationPair> = listOf(
        TranslationPair("中文", "英文"),
        TranslationPair("中文", "韩文"),
        TranslationPair("中文", "日文"),
        TranslationPair("简体中文", "繁体中文"),
        TranslationPair("中文", "俄文"),
    )

    fun translationSystemPrompt(direction: String): String =
        "你是一个专业的翻译引擎，本次翻译方向是：$direction。\n" +
            "必须遵守以下规则：\n" +
            "1. 只输出译文本身，绝对不要输出原文、解释、备注、拼音或任何补充说明；\n" +
            "2. 保留原文的换行与分段结构；\n" +
            "3. 人名、地名、专有名词使用目标语言的通行译法；\n" +
            "4. 译文要自然流畅，符合目标语言的表达习惯。"

    fun translationMessages(direction: String, source: String): List<BackendMessage> =
        listOf(
            BackendMessage(BackendRole.SYSTEM, translationSystemPrompt(direction)),
            BackendMessage(BackendRole.USER, source),
        )

    // ---------------- 一起作文 ----------------

    const val ESSAY_MIN_WORDS = 100
    const val ESSAY_MAX_WORDS = 2000

    fun essaySystemPrompt(targetWords: Int): String {
        val upper = ESSAY_MAX_WORDS
        return "你是一个中文作文写作助手。请根据用户给出的题目或要求写一篇完整的作文。\n" +
            "硬性要求：\n" +
            "1. 正文字数不少于 $targetWords 字；\n" +
            "2. 全文总字数不超过 $upper 字；\n" +
            "3. 结构完整（开头、主体、结尾），语言流畅，内容切题；\n" +
            "4. 直接输出作文正文，第一行可以是标题，不要输出字数统计、写作说明或任何额外解释。"
    }

    fun essayMessages(topic: String, targetWords: Int): List<BackendMessage> =
        listOf(
            BackendMessage(BackendRole.SYSTEM, essaySystemPrompt(targetWords)),
            BackendMessage(
                BackendRole.USER,
                "作文题目或要求：$topic\n请开始写作，不少于 $targetWords 字，且不超过 $ESSAY_MAX_WORDS 字。",
            ),
        )

    // ---------------- 海龟汤 ----------------

    const val SOUP_HINT_LIMIT = 3

    const val SOUP_PUZZLE_SYSTEM: String =
        "你是一个海龟汤（情境推理游戏）出题者。请原创一个逻辑自洽、难度适中的海龟汤谜题。\n" +
            "必须严格输出下面格式的 JSON，不要输出任何其他内容，不要使用 Markdown 代码块：\n" +
            "{\"puzzle\":\"谜面（只描述情境，不包含谜底）\",\"answer\":\"谜底（完整解释）\"}\n" +
            "要求：谜面 50-150 字，足够描述情境；谜底 50-200 字，解释合理且唯一。"

    fun soupPlaySystem(puzzle: String, answer: String): String =
        "你正在主持海龟汤推理游戏。\n" +
            "谜面：$puzzle\n" +
            "谜底（绝对保密）：$answer\n" +
            "规则：\n" +
            "1. 玩家提问时，你只能回答三种内容之一：「是」「否」或「提示：…」；\n" +
            "2. 普通提问只允许回答「是」或「否」，当提问无法用是/否回答时，回答「否」；\n" +
            "3. 只有玩家明确索要提示时，才回答「提示：…」，提示要简短且不能直接说出谜底；\n" +
            "4. 任何时候都不要泄露谜底，不要解释推理过程，不要输出其他文字。"

    fun soupGuessSystem(puzzle: String, answer: String): String =
        "你正在主持海龟汤推理游戏。\n" +
            "谜面：$puzzle\n" +
            "谜底：$answer\n" +
            "规则：玩家会给出对谜底的猜测。\n" +
            "1. 如果猜测与谜底一致或意思基本相同，只输出：「正确」；\n" +
            "2. 否则只输出：「否」；\n" +
            "3. 不要输出任何其他内容。"

    // ---------------- 成语接龙 ----------------

    const val IDIOM_OPENING_SYSTEM: String =
        "你是成语接龙游戏的出题者。请随机给出一个常见且容易接续的四字成语。\n" +
            "只输出这 4 个汉字，不要输出拼音、标点、解释或任何其他内容。"

    const val IDIOM_JUDGE_SYSTEM: String =
        "你是成语接龙游戏的裁判。规则：玩家接的成语必须是四字成语，且首字读音与上一个成语的末字相同或相近（同音不同字也算合法）。\n" +
            "请严格按下面三行格式输出，不要输出其他任何内容（不要加序号、不要加 Markdown 标记）：\n" +
            "第 1 行：VALID 或 INVALID（玩家成语是否合法）\n" +
            "第 2 行：你接的下一个四字成语；如果你接不上，只写 NONE\n" +
            "第 3 行：一句话说明（不超过 30 字）"

    fun idiomJudgeMessages(
        history: List<BackendMessage>,
        previousIdiom: String,
        playerIdiom: String,
    ): List<BackendMessage> =
        history + BackendMessage(
            BackendRole.USER,
            "上一个成语：$previousIdiom\n玩家接的成语：$playerIdiom\n请按格式给出判定与你的接龙。",
        )
}
