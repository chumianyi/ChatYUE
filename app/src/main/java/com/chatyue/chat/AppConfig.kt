package com.chatyue.chat

import android.content.Context
import android.content.SharedPreferences
import com.chatyue.chat.backend.ChatMode

/**
 * 应用级配置（2.0 起）。
 *
 * 所有配置写入 SharedPreferences，随进程重启、关机、版本升级永久保留，
 * 只有用户手动清除应用数据或卸载才会重置。
 */
object AppConfig {
    private const val PREFS_NAME = "chatyue_config"

    private const val KEY_UI_SCALE = "ui_scale"
    private const val KEY_SPLASH_ENABLED = "splash_enabled"
    private const val KEY_CHAT_MODE = "chat_mode"
    private const val KEY_SPEECH_RATE = "speech_rate"
    private const val KEY_AUTO_SPEAK = "auto_speak"
    private const val KEY_OOBE_COMPLETED = "oobe_completed"

    /** UI 尺寸档位。 */
    enum class UiScale(val label: String, val factor: Float, val summary: String) {
        STANDARD("标准", 1.0f, "默认尺寸，适合大多数机型"),
        LARGE("大", 1.12f, "字体与控件整体放大"),
        EXTRA_LARGE("特大", 1.24f, "适合视力不佳或大屏设备"),
    }

    /** 消息朗读语速档位。 */
    enum class SpeechRate(val label: String, val rate: Float, val summary: String) {
        SLOW("慢", 0.75f, "听得更清楚，适合跟读"),
        NORMAL("正常", 1.0f, "默认语速"),
        FAST("快", 1.5f, "适合已知内容快速浏览"),
    }

    /** 配置变化监听（界面需要实时响应尺寸变化）。 */
    interface Listener {
        fun onUiScaleChanged(scale: UiScale)
    }

    private var listener: Listener? = null

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun registerListener(listener: Listener?) {
        this.listener = listener
    }

    // ---------------- UI 尺寸 ----------------

    fun uiScale(context: Context): UiScale {
        val name = prefs(context).getString(KEY_UI_SCALE, null)
        return UiScale.entries.firstOrNull { it.name == name } ?: UiScale.STANDARD
    }

    fun setUiScale(context: Context, scale: UiScale) {
        prefs(context).edit().putString(KEY_UI_SCALE, scale.name).apply()
        listener?.onUiScaleChanged(scale)
    }

    // ---------------- 启动动画开关 ----------------

    /** 启动动画是否开启，默认开启。 */
    fun isSplashEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SPLASH_ENABLED, true)

    fun setSplashEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SPLASH_ENABLED, enabled).apply()
    }

    // ---------------- 朗读语速 ----------------

    /** 当前朗读语速，默认正常。 */
    fun speechRate(context: Context): SpeechRate {
        val name = prefs(context).getString(KEY_SPEECH_RATE, null)
        return SpeechRate.entries.firstOrNull { it.name == name } ?: SpeechRate.NORMAL
    }

    fun setSpeechRate(context: Context, rate: SpeechRate) {
        prefs(context).edit().putString(KEY_SPEECH_RATE, rate.name).apply()
    }

    // ---------------- 自动朗读 ----------------

    /** 生成完成后是否自动朗读最新回复，默认关闭（用户手动点喇叭）。 */
    fun isAutoSpeakEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_SPEAK, false)

    fun setAutoSpeakEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_SPEAK, enabled).apply()
    }

    // ---------------- 运行时模式偏好 ----------------

    /** 当前推理模式，默认标准思考模式。 */
    fun chatMode(context: Context): ChatMode =
        ChatMode.fromName(prefs(context).getString(KEY_CHAT_MODE, null))

    fun setChatMode(context: Context, mode: ChatMode) {
        prefs(context).edit().putString(KEY_CHAT_MODE, mode.name).apply()
    }

    // ---------------- OOBE ----------------

    /** OOBE 是否已经完成（终身仅展示一次）。 */
    fun isOobeCompleted(context: Context): Boolean =
        prefs(context).getBoolean(KEY_OOBE_COMPLETED, false)

    fun markOobeCompleted(context: Context) {
        prefs(context).edit().putBoolean(KEY_OOBE_COMPLETED, true).apply()
    }

    // ---------------- 数据管理用 ----------------

    const val PREFS_FILE_NAME = PREFS_NAME

    fun snapshot(context: Context): Map<String, Any?> {
        val all = prefs(context).all
        return all.mapValues { it.value }
    }
}
