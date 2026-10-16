package com.chatyue.chat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 「生成完成」通知栏提醒的组装与投递（2.0 新增）。
 *
 * 用户切到后台等长回答：生成完成时若应用不在前台，就发一条低声优先级的
 * 通知，点通知回到应用；没有通知权限（Android 13+ 运行时权限）或被系统
 * 限制时安全跳过，绝不影响前台使用。
 */
class GenerationNotifications(context: Context) {
    private val appContext = context.applicationContext

    /** 当前是否允许发通知：低版本始终允许，Android 13+ 看运行时权限。 */
    fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 发一条「回复完成」通知；权限不足或被系统静默时返回 false，不抛异常。
     *
     * @param conversationTitle 会话标题，用于通知标题。
     * @param content 回复正文，压成预览文案。
     */
    fun show(conversationTitle: String, content: String): Boolean {
        if (!canPost()) return false
        ensureChannel()
        val notification = NotificationCompat.Builder(appContext, GenerationNotifier.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_check_white)
            .setContentTitle(GenerationNotifier.title(conversationTitle))
            .setContentText(GenerationNotifier.preview(content))
            .setStyle(NotificationCompat.BigTextStyle().bigText(GenerationNotifier.preview(content)))
            .setAutoCancel(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openAppIntent())
            .build()
        return try {
            NotificationManagerCompat.from(appContext)
                .notify(GenerationNotifier.NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            // 通知权限在检查之后被撤走等极端情况：静默降级。
            false
        }
    }

    /** 回到前台时清掉残留的提醒，避免用户回来还挂着一条过期通知。 */
    fun cancel() {
        NotificationManagerCompat.from(appContext).cancel(GenerationNotifier.NOTIFICATION_ID)
    }

    /** 点击通知回到应用：复用已有实例，不新开任务栈。 */
    private fun openAppIntent(): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        // FLAG_IMMUTABLE 需要 API 23，低版本置 0 兼容。
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getActivity(appContext, 0, intent, flags)
    }

    /** 安卓 8.0+ 建低声提醒渠道：无声、不悬浮，不打断用户。 */
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(GenerationNotifier.CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            GenerationNotifier.CHANNEL_ID,
            appContext.getString(R.string.notification_channel_generation),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = appContext.getString(R.string.notification_channel_generation_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }
}
