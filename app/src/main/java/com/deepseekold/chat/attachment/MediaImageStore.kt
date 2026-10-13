package com.deepseekold.chat.attachment

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import com.deepseekold.chat.R

data class MediaImage(
    val id: Long,
    val name: String,
    val uri: Uri,
)

/** 读取设备相册中的全部图片。 */
object MediaImageStore {

    fun queryAll(context: Context): List<MediaImage> {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        val fallbackName = context.getString(R.string.pick_image_title)

        val images = mutableListOf<MediaImage>()
        val cursor: Cursor? = runCatching {
            context.contentResolver.query(collection, projection, null, null, sortOrder)
        }.getOrNull()
        cursor?.use {
            val idColumn = it.getColumnIndex(MediaStore.Images.Media._ID)
            val nameColumn = it.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
            while (it.moveToNext()) {
                if (idColumn < 0) continue
                val id = it.getLong(idColumn)
                val name = if (nameColumn >= 0) {
                    it.getString(nameColumn) ?: fallbackName
                } else {
                    fallbackName
                }
                images += MediaImage(
                    id = id,
                    name = name,
                    uri = ContentUris.withAppendedId(collection, id),
                )
            }
        }
        return images
    }

    /** 读取文本附件（限制大小，避免超大文件把界面拖死）。 */
    fun readText(context: Context, uri: Uri, maxBytes: Int = MAX_TEXT_BYTES): String? {
        val stream = context.contentResolver.openInputStream(uri) ?: return null
        return stream.use { input ->
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            var total = 0
            while (true) {
                val read = input.read(chunk)
                if (read <= 0) break
                total += read
                if (total > maxBytes) return@use null
                buffer.write(chunk, 0, read)
            }
            buffer.toByteArray().toString(Charsets.UTF_8)
        }
    }

    /** 尝试从 URI 取一个可读的文件名。 */
    fun displayName(context: Context, uri: Uri): String {
        val cursor = runCatching {
            context.contentResolver.query(uri, null, null, null, null)
        }.getOrNull()
        cursor?.use {
            val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && it.moveToFirst()) {
                return it.getString(index) ?: fallbackName(uri)
            }
        }
        return fallbackName(uri)
    }

    private fun fallbackName(uri: Uri): String =
        uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"

    const val MAX_TEXT_BYTES = 200 * 1024
}
