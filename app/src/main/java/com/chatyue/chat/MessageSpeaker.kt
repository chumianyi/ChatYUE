package com.chatyue.chat

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * 消息朗读：基于系统 TTS 把消息正文读出来。
 *
 * 封装初始化、语言设置与释放逻辑；引擎未就绪时所有调用安全降级为空操作。
 */
class MessageSpeaker(context: Context) {
    private var ready = false

    private val tts = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            val result = setLanguageSafely(Locale.CHINA)
            ready = result >= TextToSpeech.LANG_AVAILABLE
        }
    }

    private fun setLanguageSafely(locale: Locale): Int =
        try {
            tts.setLanguage(locale)
        } catch (_: IllegalArgumentException) {
            TextToSpeech.LANG_NOT_SUPPORTED
        }

    /** 朗读一段文本；会打断当前正在朗读的内容。 */
    fun speak(text: String) {
        if (text.isBlank()) return
        if (!ready) {
            Log.w(TAG, "TTS 未就绪，忽略朗读请求")
            return
        }
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
    }
}
