package com.chatyue.chat

/**
 * 编辑用户消息时的正文拆分与重组（2.0 新增「编辑消息」）。
 *
 * 一条用户消息 = 正文 + 附件块（附件块由 [AttachmentBlockBuilder] 生成，
 * 总以 "\n\n[图片附件] " 或 "\n\n[文本附件] " 接在正文之后）。
 * 编辑时只让用户改正文，附件块原样保留，避免用户误删模型不可见的附件说明。
 *
 * 纯函数实现，不依赖 Android 框架，便于单元测试。
 */
object EditMessageText {

    /** 附件块起始标记，需与 [AttachmentBlockBuilder] 的输出保持同步。 */
    private val ATTACHMENT_MARKERS = listOf("\n\n[图片附件] ", "\n\n[文本附件] ")

    /** 拆分结果：正文 + 附件块（附件块保留原有前导换行）。 */
    data class Parts(val body: String, val attachmentBlock: String)

    /**
     * 把消息内容拆成正文与附件块。
     *
     * 没有附件块时附件块为空串；多个附件块同时存在时，以最靠前的标记切开，
     * 后续块全部留在附件块里。
     */
    fun split(content: String): Parts {
        val index = ATTACHMENT_MARKERS
            .mapNotNull { marker -> content.indexOf(marker).takeIf { it >= 0 } }
            .minOrNull()
            ?: return Parts(body = content, attachmentBlock = "")
        return Parts(
            body = content.substring(0, index),
            attachmentBlock = content.substring(index),
        )
    }

    /**
     * 把编辑后的正文与保留的附件块重新拼成消息内容。
     *
     * 正文去首尾空白；正文为空时只保留附件块，不塞进空白正文。
     */
    fun merge(body: String, attachmentBlock: String): String {
        val trimmed = body.trim()
        return when {
            trimmed.isEmpty() -> attachmentBlock.trim()
            attachmentBlock.isEmpty() -> trimmed
            else -> trimmed + attachmentBlock
        }
    }
}
