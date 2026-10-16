package com.chatyue.chat

import android.content.ContentValues
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import java.io.File
import java.io.FileOutputStream

/**
 * 全局背景配置（2.0 新增）。
 *
 * - 默认纯白色，保留原版简约视觉
 * - 内置多款低饱和度浅色预设（淡粉 / 淡绿 等）
 * - 支持导入本地图片作为全局背景，自动居中裁剪铺满
 * - 图片同时写入设备公共存储 `Pictures/ChatYUE/` 与应用外部私有目录，
 *   保证卸载重装外、清缓存、重启、升级都不会丢失
 */
object AppearanceStore {
    private const val PREFS_NAME = "chatyue_appearance"
    private const val KEY_MODE = "background_mode"
    private const val KEY_PRESET = "background_preset"
    private const val KEY_IMAGE = "background_image_path"

    private const val FILE_NAME = "background.png"
    private const val PUBLIC_DIR_NAME = "ChatYUE"

    /** 背景来源。 */
    enum class Mode { PRESET, IMAGE }

    /** 内置低饱和度浅色预设。 */
    enum class Preset(val label: String, val hex: String) {
        WHITE("纯白", "#FFFFFF"),
        PINK("淡粉", "#FDF3F5"),
        GREEN("淡绿", "#F1F8F3"),
        BLUE("淡蓝", "#F1F5FC"),
        YELLOW("淡黄", "#FDF8EC"),
        PURPLE("淡紫", "#F6F2FB"),
        CYAN("淡青", "#EFF9F9"),
        GRAY("浅灰", "#F5F6F8"),
        ;

        val color: Int get() = Color.parseColor(hex)

        companion object {
            fun fromName(value: String?): Preset =
                entries.firstOrNull { it.name == value } ?: WHITE
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun mode(context: Context): Mode {
        val name = prefs(context).getString(KEY_MODE, null)
        return Mode.entries.firstOrNull { it.name == name } ?: Mode.PRESET
    }

    fun preset(context: Context): Preset =
        Preset.fromName(prefs(context).getString(KEY_PRESET, null))

    /** 自定义背景图的本地副本路径；未设置时返回 null。 */
    fun imageFile(context: Context): File? {
        val path = prefs(context).getString(KEY_IMAGE, null) ?: return null
        val file = File(path)
        return if (file.isFile) file else null
    }

    fun hasImage(context: Context): Boolean = imageFile(context) != null

    // ---------------- 设置 ----------------

    fun setPreset(context: Context, preset: Preset) {
        prefs(context).edit()
            .putString(KEY_MODE, Mode.PRESET.name)
            .putString(KEY_PRESET, preset.name)
            .apply()
    }

    /**
     * 导入一张本地图片作为全局背景。
     *
     * @return 是否导入成功
     */
    fun importImage(context: Context, uri: Uri): Boolean {
        val bitmap = decodeSampled(context, uri) ?: return false
        val saved = savePrivate(context, bitmap)
        if (!saved) {
            bitmap.recycle()
            return false
        }
        // 同时写入设备公共存储，方便用户在相册中查看与保留。
        runCatching { savePublic(context, bitmap) }
        bitmap.recycle()
        prefs(context).edit()
            .putString(KEY_MODE, Mode.IMAGE.name)
            .apply()
        return true
    }

    fun clearImage(context: Context) {
        imageFile(context)?.delete()
        prefs(context).edit()
            .remove(KEY_IMAGE)
            .putString(KEY_MODE, Mode.PRESET.name)
            .apply()
    }

    // ---------------- 应用背景 ----------------

    /** 把当前背景应用到指定视图。 */
    fun applyTo(context: Context, view: View) {
        view.background = backgroundDrawable(context)
    }

    /** 当前背景对应的 Drawable。 */
    fun backgroundDrawable(context: Context): Drawable {
        if (mode(context) == Mode.IMAGE) {
            imageFile(context)?.let { file ->
                val bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath) }
                    .getOrNull()
                if (bitmap != null) {
                    return BitmapDrawable(context.resources, bitmap).apply {
                        // 居中裁剪，铺满整个界面
                        gravity = android.view.Gravity.FILL
                        setFilterBitmap(true)
                    }
                }
            }
        }
        return ColorDrawable(preset(context).color)
    }

    /** 当前背景的主色（用于状态栏等取色）。 */
    fun backgroundColor(context: Context): Int = preset(context).color

    /** 弹窗、卡片等半透明浮层需要的底色（跟随深色模式）。 */
    fun surfaceColor(context: Context): Int {
        val night = (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return if (night) Color.parseColor("#FF1C1F24") else Color.WHITE
    }

    // ---------------- 文件读写 ----------------

    private fun privateFile(context: Context): File =
        File(context.getExternalFilesDir("background") ?: context.filesDir, FILE_NAME)

    private fun savePrivate(context: Context, bitmap: Bitmap): Boolean {
        return runCatching {
            val file = privateFile(context)
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            prefs(context).edit().putString(KEY_IMAGE, file.absolutePath).apply()
            true
        }.getOrDefault(false)
    }

    private fun savePublic(context: Context, bitmap: Bitmap): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/$PUBLIC_DIR_NAME",
                )
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return false
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            } ?: return false
            true
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                PUBLIC_DIR_NAME,
            )
            if (!dir.exists() && !dir.mkdirs()) return false
            FileOutputStream(File(dir, FILE_NAME)).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            true
        }
    }

    /** 读取时按需下采样，避免超大图占用过多内存。 */
    private fun decodeSampled(context: Context, uri: Uri): Bitmap? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            var sample = 1
            val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
            while (maxSide / sample > MAX_BACKGROUND_SIDE) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        }.getOrNull()
    }

    private const val MAX_BACKGROUND_SIDE = 2400

    const val PREFS_FILE_NAME = PREFS_NAME
}
