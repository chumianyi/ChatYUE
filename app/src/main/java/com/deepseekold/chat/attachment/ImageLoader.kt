package com.deepseekold.chat.attachment

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.util.concurrent.Executors

/**
 * 极简异步缩略图加载：后台按目标尺寸二次采样解码，主线程回填，带内存缓存。
 */
object ImageLoader {
    private val executor = Executors.newFixedThreadPool(4) { runnable ->
        Thread(runnable, "deepseek-image-loader").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    private val cache = LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtLeast(4 * 1024 * 1024),
    ) {
        it.byteCount
    }

    fun load(context: Context, uri: Uri, targetSizePx: Int, imageView: ImageView) {
        val key = "$uri@$targetSizePx"
        imageView.tag = key

        cache.get(key)?.let { bitmap ->
            imageView.setImageBitmap(bitmap)
            return
        }

        val appContext = context.applicationContext
        executor.execute {
            val bitmap = runCatching { decode(appContext, uri, targetSizePx) }.getOrNull()
            if (bitmap == null) return@execute
            cache.put(key, bitmap)
            mainHandler.post {
                if (imageView.tag == key) {
                    imageView.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun decode(context: Context, uri: Uri, targetSizePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetSizePx)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }

    private fun sampleSize(width: Int, height: Int, target: Int): Int {
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / 2 >= target) {
            longest /= 2
            sample *= 2
        }
        return sample
    }
}
