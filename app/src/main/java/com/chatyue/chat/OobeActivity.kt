package com.chatyue.chat

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.chatyue.chat.databinding.ActivityOobeBinding

/**
 * OOBE 首次开机引导（2.0 新增）。
 *
 * 触发条件严格唯一：仅在安装 2.0 后第一次全新打开应用时展示，
 * 二次启动、重启、覆盖安装、进程重载都不会再次出现。
 *
 * 页面按固定时序执行四段动画，全程无需用户干预：
 * 1. 「你好」自中心平滑划出 → 停留 → 淡出
 * 2. 「我们正在为您准备中」淡入 → 停留 → 淡出
 * 3. 「你可以配置你的客户端」常驻，下方为两项基础配置，底部浮现对勾确认按钮
 * 4. 「不要着急，很快就好」常驻，静默等待 3 秒完成初始化后进入主页面
 */
class OobeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOobeBinding
    private val handler = Handler(Looper.getMainLooper())
    private var finished = false
    private var confirmShown = false
    private var userTouchedAnyConfig = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOobeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // OOBE 不可跳过：拦截返回键。
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = Unit
            },
        )

        setupConfigOptions()
        startGreetingStage()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---------------- 配置项 ----------------

    private fun setupConfigOptions() {
        // 默认值：界面标准尺寸、启动动画开启。
        binding.uiScaleSwitch.isChecked = AppConfig.uiScale(this) != AppConfig.UiScale.STANDARD
        binding.splashSwitch.isChecked = AppConfig.isSplashEnabled(this)

        binding.uiScaleSwitch.setOnCheckedChangeListener { _, checked ->
            AppConfig.setUiScale(
                this,
                if (checked) AppConfig.UiScale.LARGE else AppConfig.UiScale.STANDARD,
            )
            onConfigTouched()
        }
        binding.splashSwitch.setOnCheckedChangeListener { _, checked ->
            AppConfig.setSplashEnabled(this, checked)
            onConfigTouched()
        }
        binding.confirmButton.setOnClickListener { startFinishingStage() }

        // 兜底：若用户 3.5 秒内没有任何操作，也浮现确认按钮，避免流程卡死。
        handler.postDelayed({
            if (!confirmShown && !isFinishing) showConfirmButton()
        }, CONFIRM_AUTO_DELAY_MILLIS)
    }

    private fun onConfigTouched() {
        userTouchedAnyConfig = true
        if (!confirmShown) showConfirmButton()
    }

    private fun showConfirmButton() {
        if (confirmShown || isFinishing) return
        confirmShown = true
        binding.confirmButton.apply {
            visibility = View.VISIBLE
            alpha = 0f
            animate().alpha(1f).setDuration(FADE_IN).start()
        }
    }

    // ---------------- 阶段 1：你好 ----------------

    private fun startGreetingStage() {
        val greeting = binding.greetingText
        greeting.visibility = View.VISIBLE
        greeting.alpha = 0f
        greeting.scaleX = 0.86f
        greeting.scaleY = 0.86f
        greeting.translationY = dp(18)

        greeting.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(ENTER_DURATION)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                handler.postDelayed({
                    if (isFinishing) return@postDelayed
                    fadeOut(greeting) { startPreparingStage() }
                }, GREETING_HOLD_MILLIS)
            }
            .start()
    }

    // ---------------- 阶段 2：我们正在为您准备中 ----------------

    private fun startPreparingStage() {
        val text = binding.preparingText
        text.visibility = View.VISIBLE
        text.alpha = 0f
        text.animate()
            .alpha(1f)
            .setDuration(FADE_IN)
            .withEndAction {
                handler.postDelayed({
                    if (isFinishing) return@postDelayed
                    fadeOut(text) { startConfigStage() }
                }, PREPARING_HOLD_MILLIS)
            }
            .start()
    }

    // ---------------- 阶段 3：配置面板 ----------------

    private fun startConfigStage() {
        val panel = binding.configPanel
        panel.visibility = View.VISIBLE
        panel.alpha = 0f
        panel.animate()
            .alpha(1f)
            .setDuration(FADE_IN)
            .start()
    }

    // ---------------- 阶段 4：初始化并进入主页面 ----------------

    private fun startFinishingStage() {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)

        val panel = binding.configPanel
        val finishing = binding.finishingText

        panel.animate()
            .alpha(0f)
            .setDuration(FADE_OUT)
            .withEndAction {
                panel.visibility = View.GONE
                binding.confirmButton.animate()
                    .alpha(0f)
                    .setDuration(FADE_OUT)
                    .withEndAction { binding.confirmButton.visibility = View.GONE }
                    .start()

                finishing.visibility = View.VISIBLE
                finishing.alpha = 0f
                finishing.animate()
                    .alpha(1f)
                    .setDuration(FADE_IN)
                    .withEndAction { runInitialization() }
                    .start()
            }
            .start()
    }

    /**
     * 静默等待 3 秒完成全局资源加载、配置写入、数据库与缓存初始化。
     */
    private fun runInitialization() {
        // 配置在此刻落盘（开关切换时已实时写入，这里做一次确认性写回）。
        AppConfig.setSplashEnabled(this, binding.splashSwitch.isChecked)
        AppConfig.setUiScale(
            this,
            if (binding.uiScaleSwitch.isChecked) {
                AppConfig.UiScale.LARGE
            } else {
                AppConfig.UiScale.STANDARD
            },
        )

        // 触发一次数据库初始化，确保后续进入主页时已就绪。
        Thread {
            runCatching {
                val repo = LocalChatRepository(applicationContext)
                repo.readableDatabase
                repo.close()
            }
        }.apply { isDaemon = true }.start()

        handler.postDelayed({
            if (isFinishing) return@postDelayed
            AppConfig.markOobeCompleted(this)
            finishToMain()
        }, INITIALIZE_WAIT_MILLIS)
    }

    private fun finishToMain() {
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
            ),
        )
        finish()
    }

    // ---------------- 工具 ----------------

    private fun fadeOut(view: View, endAction: () -> Unit) {
        view.animate()
            .alpha(0f)
            .setDuration(FADE_OUT)
            .withEndAction {
                view.visibility = View.GONE
                endAction()
            }
            .start()
    }

    private fun dp(value: Int): Float = value * resources.displayMetrics.density

    private companion object {
        const val ENTER_DURATION = 680L
        const val FADE_IN = 420L
        const val FADE_OUT = 360L
        const val GREETING_HOLD_MILLIS = 1_100L
        const val PREPARING_HOLD_MILLIS = 1_200L
        const val CONFIRM_AUTO_DELAY_MILLIS = 3_500L
        const val INITIALIZE_WAIT_MILLIS = 3_000L
    }
}
