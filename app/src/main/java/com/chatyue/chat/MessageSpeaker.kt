package com.chatyue.chat

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * 消息朗读：基于系统 TTS 把消息正文读出来。
 *
 * 封装初始化、语言设置与释放逻辑；引擎未就绪时所有调用安全降级为空操作。
 * 语速由用户在设置中选择（[AppConfig.SpeechRate]），新建实例时读入。
 */
class MessageSpeaker(context: Context, speechRate: Float = 1.0f) {
    private var ready = false

    private val tts = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            val result = setLanguageSafely(Locale.CHINA)
            ready = result >= TextToSpeech.LANG_AVAILABLE
            // 语速放在就绪后应用：某些引擎在初始化前设置会被重置。
            applySpeechRate()
        }
    }

    /** 当前语速倍数（0.5 ~ 2.0，1.0 为正常）。 */
    @Volatile
    private var speechRate: Float = speechRate.coerceIn(MIN_RATE, MAX_RATE)

    private fun setLanguageSafely(locale: Locale): Int =
        try {
            tts.setLanguage(locale)
        } catch (_: IllegalArgumentException) {
            TextToSpeech.LANG_NOT_SUPPORTED
        }

    private fun applySpeechRate() {
        try {
            tts.setSpeechRate(speechRate)
        } catch (_: IllegalArgumentException) {
            Log.w(TAG, "引擎不支持当前语速：$speechRate")
        }
    }

    /** 用户在设置里切换语速后调用：即时生效，不必重建实例。 */
    fun updateSpeechRate(rate: Float) {
        speechRate = rate.coerceIn(MIN_RATE, MAX_RATE)
        applySpeechRate()
    }

    /** 朗读一段文本；会打断当前正在朗读的内容。 */
    fun speak(text: String) {
        if (text.isBlank()) return
        if (!ready) {
            Log.w(TAG, "TTS 未就绪，忽略朗读请求")
            return
        }
        // 每次朗读前重申语速：朗读期间用户可能在设置里改了档位。
        applySpeechRate()
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    /** 停止当前朗读。 */
    fun stop() {
        if (!ready) return
        tts.stop()
    }

    fun release() {
        ready = false
        tts.stop()
        tts.shutdown()
    }

    private companion object {
        const val TAG = "MessageSpeaker"
        const val UTTERANCE_ID = "chatyue_message_speak"

        /** TTS 引擎普遍接受 0.5 ~ 2.0 的语速区间。 */
        const val MIN_RATE = 0.5f
        const val MAX_RATE = 2.0f
    }
}
