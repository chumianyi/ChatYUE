package com.chatyue.chat

/**
 * 把待发送的附件拼成用户消息里的说明块（2.0 新增多图支持）。
 *
 * 当前模型只识别文本：图片以"附件说明"的形式告知模型有几张、各叫什么，
 * 文本附件则把内容直接内联，供模型阅读。
 *
 * 纯函数实现，不依赖 Android 框架，便于单元测试。
 */
object AttachmentBlockBuilder {

    /** 最多允许携带的图片附件张数。 */
    const val MAX_IMAGE_ATTACHMENTS = 9

    /**
     * @param imageNames 图片文件名列表，保持用户选择顺序。
     * @param textName 文本附件文件名；没有则为 null。
     * @param textContent 文本附件内容；没有则为 null。
     */
    fun build(
        imageNames: List<String> = emptyList(),
        textName: String? = null,
        textContent: String? = null,
    ): String {
        val block = StringBuilder()
        if (imageNames.isNotEmpty()) {
            block.append("\n\n[图片附件] ")
            when (imageNames.size) {
                1 -> block.append(imageNames.first())
                else -> {
                    block.append("共 ").append(imageNames.size).append(" 张：")
                    imageNames.forEachIndexed { index, name ->
                        block.append('\n').append(index + 1).append("、").append(name)
                    }
                }
            }
            block.append("（本模型仅支持识别文本，无法识别图片内容）")
        }
        if (!textContent.isNullOrEmpty()) {
            block.append("\n\n[文本附件] ")
                .append(textName.orEmpty())
                .append('\n')
                .append(textContent)
                .append("\n[/文本附件]")
        }
        return block.toString()
    }
}
