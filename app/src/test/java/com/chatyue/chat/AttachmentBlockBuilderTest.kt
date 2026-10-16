package com.chatyue.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentBlockBuilderTest {
    @Test
    fun noAttachments_produceEmptyBlock() {
        assertEquals("", AttachmentBlockBuilder.build())
    }

    @Test
    fun singleImage_keepsLegacyFormat() {
        // 单图时保持旧版格式，历史会话的展示逻辑不受影响。
        assertEquals(
            "\n\n[图片附件] photo.jpg（本模型仅支持识别文本，无法识别图片内容）",
            AttachmentBlockBuilder.build(imageNames = listOf("photo.jpg")),
        )
    }

    @Test
    fun multipleImages_numberedInOrder() {
        assertEquals(
            "\n\n[图片附件] 共 3 张：\n1、a.jpg\n2、b.jpg\n3、c.jpg" +
                "（本模型仅支持识别文本，无法识别图片内容）",
            AttachmentBlockBuilder.build(imageNames = listOf("a.jpg", "b.jpg", "c.jpg")),
        )
    }

    @Test
    fun textAttachment_inlinesContent() {
        assertEquals(
            "\n\n[文本附件] notes.txt\n第一行\n第二行\n[/文本附件]",
            AttachmentBlockBuilder.build(
                textName = "notes.txt",
                textContent = "第一行\n第二行",
            ),
        )
    }

    @Test
    fun imagesAndText_concatenateBothBlocks() {
        val block = AttachmentBlockBuilder.build(
            imageNames = listOf("a.jpg", "b.jpg"),
            textName = "n.txt",
            textContent = "正文",
        )
        // 图片块在前、文本块在后，按用户添加附件的习惯顺序。
        assertEquals(
            "\n\n[图片附件] 共 2 张：\n1、a.jpg\n2、b.jpg" +
                "（本模型仅支持识别文本，无法识别图片内容）" +
                "\n\n[文本附件] n.txt\n正文\n[/文本附件]",
            block,
        )
    }

    @Test
    fun blankTextContent_isIgnored() {
        assertEquals("", AttachmentBlockBuilder.build(textName = "n.txt", textContent = ""))
        assertEquals("", AttachmentBlockBuilder.build(textName = "n.txt", textContent = null))
    }
}
