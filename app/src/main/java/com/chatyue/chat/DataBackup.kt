package com.chatyue.chat

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 数据备份 / 恢复（2.0）。
 *
 * 导出内容：
 * - chatyue.db：全部会话与消息
 * - shared_prefs/chatyue_config.xml：界面尺寸、启动动画、模式偏好、OOBE 状态
 * - shared_prefs/chatyue_appearance.xml：全局背景（预设/自定义图片路径）
 *
 * 备份文件写入公共下载目录 `Download/ChatYUE/chatyue_backup.zip`，
 * 用户可在系统文件管理中随时找到。
 */
object DataBackup {

    const val DB_FILE_NAME = "chatyue.db"
    const val CONFIG_PREFS_NAME = AppConfig.PREFS_FILE_NAME
    const val APPEARANCE_PREFS_NAME = AppearanceStore.PREFS_FILE_NAME

    private const val BACKUP_DIR_NAME = "ChatYUE"
    private const val BACKUP_FILE_NAME = "chatyue_backup.zip"

    /** 导出备份，返回备份文件的可读位置描述。 */
    fun export(context: Context): Result<String> = runCatching {
        val dbFile = context.getDatabasePath(DB_FILE_NAME)
        require(dbFile.isFile) { "本地数据库不存在" }

        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        val configFile = File(prefsDir, "$CONFIG_PREFS_NAME.xml")
        val appearanceFile = File(prefsDir, "$APPEARANCE_PREFS_NAME.xml")

        val cacheZip = File(context.cacheDir, BACKUP_FILE_NAME)
        if (cacheZip.exists()) cacheZip.delete()
        ZipOutputStream(FileOutputStream(cacheZip).buffered()).use { zip ->
            listOf(
                DB_FILE_NAME to dbFile,
                "$CONFIG_PREFS_NAME.xml" to configFile,
                "$APPEARANCE_PREFS_NAME.xml" to appearanceFile,
            ).forEach { (entryName, file) ->
                if (file.isFile) {
                    zip.putNextEntry(ZipEntry(entryName))
                    FileInputStream(file).use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }

        val location = writeToPublicDownloads(context, cacheZip)
        cacheZip.delete()
        location
    }

    /** 从用户选择的备份文件恢复，覆盖前需由调用方确认。 */
    fun import(context: Context, uri: Uri): Result<Unit> = runCatching {
        val entries = mutableSetOf<String>()
        context.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) entries.add(entry.name)
                    entry = zip.nextEntry
                }
            }
        } ?: error("无法读取备份文件")
        require(entries.any()) { "备份文件为空" }

        // 先解压到 cache，再由调用方执行覆盖（保证原子性）。
        val staging = File(context.cacheDir, "import_staging").apply {
            deleteRecursively()
            mkdirs()
        }
        context.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val safeName = File(entry.name).name
                        if (safeName == DB_FILE_NAME ||
                            safeName == "$CONFIG_PREFS_NAME.xml" ||
                            safeName == "$APPEARANCE_PREFS_NAME.xml"
                        ) {
                            val out = File(staging, safeName)
                            FileOutputStream(out).use { zip.copyTo(it) }
                        }
                    }
                    entry = zip.nextEntry
                }
            }
        }
        Unit
    }

    /** 把缓存中的临时备份写入公共下载目录，返回位置描述。 */
    private fun writeToPublicDownloads(context: Context, zip: File): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, BACKUP_FILE_NAME)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOWNLOADS}/$BACKUP_DIR_NAME",
                )
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("无法创建备份文件")
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(zip).use { it.copyTo(out) }
            } ?: error("无法写入备份文件")
            "Download/$BACKUP_DIR_NAME/$BACKUP_FILE_NAME"
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                BACKUP_DIR_NAME,
            )
            if (!dir.exists() && !dir.mkdirs()) error("无法创建备份目录")
            val target = File(dir, BACKUP_FILE_NAME)
            FileInputStream(zip).use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
            target.absolutePath
        }
    }

    /** 执行覆盖恢复（调用方需已确认）。恢复完成后应重启应用。 */
    fun applyRestore(context: Context): Result<Unit> = runCatching {
        val staging = File(context.cacheDir, "import_staging")
        val dbStaged = File(staging, DB_FILE_NAME)
        require(dbStaged.isFile) { "备份中缺少数据库文件" }

        val dbFile = context.getDatabasePath(DB_FILE_NAME)
        dbStaged.copyTo(dbFile, overwrite = true)
        // SQLite 的 -wal/-shm 陪文件如果残留会干扰新库，直接清掉。
        File(dbFile.absolutePath + "-wal").delete()
        File(dbFile.absolutePath + "-shm").delete()

        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        if (!prefsDir.exists()) prefsDir.mkdirs()
        listOf(CONFIG_PREFS_NAME, APPEARANCE_PREFS_NAME).forEach { name ->
            val staged = File(staging, "$name.xml")
            if (staged.isFile) staged.copyTo(File(prefsDir, "$name.xml"), overwrite = true)
        }
        staging.deleteRecursively()
        Unit
    }
}
