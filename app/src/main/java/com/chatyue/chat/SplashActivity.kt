package com.chatyue.chat

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.chatyue.chat.databinding.ActivitySplashBinding

/**
 * 启动画面（2.0 重构）。
 *
 * - 纯白底层背景，无多余装饰
 * - 视觉主体为抠图后的二次元官方形象
 * - 双层文字：ChatYUE / 给你更好的体验，左侧固定放置官方 AI 图标
 * - 固定静态展示 3 秒整，随后平滑过渡到后续流程
 *
 * 后续流程顺序：OOBE 首次引导（仅首次）→ 用户协议（仅首次）→ 主页面。
 */
class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private val handler = Handler(Looper.getMainLooper())
    private var routed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.splashMascot.alpha = 1f

        // 用户可在 OOBE / 设置中关闭启动动画，关闭后直接进入后续流程。
        if (!AppConfig.isSplashEnabled(this)) {
            route()
            return
        }

        // 固定展示时长：3 秒整。
        handler.postDelayed({ route() }, SPLASH_DURATION_MILLIS)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun route() {
        if (routed) return
        routed = true

        val next: Class<*> = when {
            // OOBE 终身仅展示一次：安装 2.0 后首次打开才会走到这里。
            !AppConfig.isOobeCompleted(this) -> OobeActivity::class.java
            !AgreementStore.isAccepted(this) -> AgreementActivity::class.java
            else -> MainActivity::class.java
        }

        startActivity(
            Intent(this, next).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
            ),
        )
        finish()
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }

    private companion object {
        const val SPLASH_DURATION_MILLIS = 3_000L
    }
}
