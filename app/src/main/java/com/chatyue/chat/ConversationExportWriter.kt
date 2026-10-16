package com.chatyue.chat

import android.content.Context
import java.io.File

/**
 * 导出稿落盘：统一写到应用缓存的 exports 目录，
 * 由 FileProvider 以只读方式分享给其他应用。
 */
object ConversationExportWriter {
    private const val EXPORT_DIR_NAME = "exports"

    fun write(context: Context, fileName: String, content: String): File {
        val dir = File(context.cacheDir, EXPORT_DIR_NAME).apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeText(content)
        return file
    }
}
