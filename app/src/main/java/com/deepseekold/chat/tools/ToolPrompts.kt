package com.deepseekold.chat.tools

import com.deepseekold.chat.backend.BackendMessage
import com.deepseekold.chat.backend.BackendRole

/**
 * 侧边栏工具的提示词与选项定义。
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

    /**
     * 翻译提示词：强制只输出译文本身。
     * 注意：思考过程不展示，也不允许混进译文。
     */
    fun translationSystemPrompt(direction: String): String =
        "你是翻译引擎。本次翻译方向：$direction。\n" +
            "必须严格遵守：\n" +
            "1. 仅输出翻译内容，不输出其他任何话；\n" +
            "2. 禁止输出原文、解释、注释、拼音、音标、编号、引号包裹、字数统计、" +
            "中括号标记、Markdown 代码块或任何形式的补充说明；\n" +
            "3. 不要复述我的要求，不要说「以下是译文」之类的话，直接给出译文；\n" +
            "4. 保留原文的换行与分段；\n" +
            "5. 人名、地名、专有名词使用目标语言的通行译法。"

    fun translationMessages(direction: String, source: String): List<BackendMessage> =
        listOf(
            BackendMessage(BackendRole.SYSTEM, translationSystemPrompt(direction)),
            BackendMessage(BackendRole.USER, source),
        )

    // ---------------- 一起作文 ----------------

    const val ESSAY_MIN_WORDS = 100
    const val ESSAY_MAX_WORDS = 2000

    /** 写作风格（5 种）。 */
    data class EssayStyle(val name: String, val guidance: String)

    val ESSAY_STYLES: List<EssayStyle> = listOf(
        EssayStyle("散文", "以抒情与感悟为主，语言优美、有意境，句式自由，形散而神不散"),
        EssayStyle("记叙文", "以叙事为主，时间线清楚，有场景、细节与情感变化"),
        EssayStyle("小说", "有完整的情节推进、场景描写与人物对话，注重冲突与节奏"),
        EssayStyle("议论文", "观点明确，论据充分，按「提出观点—论证—总结」的结构展开"),
        EssayStyle("说明文", "客观准确，条理清晰，多用分类、举例、比较等方式说明事物"),
    )

    /** 输出语言（默认中文）。 */
    data class EssayLanguage(val name: String, val prompt: String)

    val ESSAY_LANGUAGES: List<EssayLanguage> = listOf(
        EssayLanguage("中文", "简体中文"),
        EssayLanguage("英语", "English"),
    )

    fun essaySystemPrompt(
        targetWords: Int,
        style: EssayStyle,
        language: EssayLanguage,
    ): String {
        return "你是中文作文写作助手。请按用户给出的题目或要求写一篇作文。\n" +
            "写作风格：${style.name}——${style.guidance}。\n" +
            "输出语言：${language.prompt}。\n" +
            "硬性要求：\n" +
            "1. 正文不少于 $targetWords 字（英文按词计），且全文不超过 $ESSAY_MAX_WORDS；\n" +
            "2. 结构完整（开头、主体、结尾），内容切题，语言流畅；\n" +
            "3. 直接输出作文正文，第一行可以是标题；\n" +
            "4. 不要输出字数统计、写作说明、批注或任何额外解释，也不要说「好的，我来写」之类的话。"
    }

    fun essayMessages(
        topic: String,
        targetWords: Int,
        style: EssayStyle,
        language: EssayLanguage,
    ): List<BackendMessage> =
        listOf(
            BackendMessage(BackendRole.SYSTEM, essaySystemPrompt(targetWords, style, language)),
            BackendMessage(
                BackendRole.USER,
                "题目或要求：$topic\n（${style.name}，${language.name}，不少于 $targetWords 字，不超过 $ESSAY_MAX_WORDS 字）",
            ),
        )

}
