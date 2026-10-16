package com.chatyue.chat.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Windows 系统同款的动态渐变背景。
 *
 * 实现方式：在浅色底上叠加数团柔和的径向光晕，光晕沿平滑的圆周轨迹缓慢漂移，
 * 节奏均匀、无闪烁，整体呈现系统级的高级简约质感。
 */
class GradientBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private data class Blob(
        val baseX: Float,
        val baseY: Float,
        val radiusRatio: Float,
        val phase: Float,
        val color: Int,
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 底色：纯净的冷白，保证文字对比度。 */
    private val baseColor = Color.parseColor("#F7F9FC")

    private val blobs = listOf(
        Blob(0.22f, 0.24f, 0.78f, 0.0f, Color.parseColor("#7FB4F0")),
        Blob(0.80f, 0.30f, 0.72f, 2.1f, Color.parseColor("#9C8CF0")),
        Blob(0.60f, 0.82f, 0.80f, 4.2f, Color.parseColor("#6FC7D8")),
    )

    private var phase = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 18_000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animator.isStarted) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        canvas.drawColor(baseColor)

        val base = min(width, height).toFloat()
        val angle = phase * 2f * Math.PI.toFloat()

        for (blob in blobs) {
            val t = angle + blob.phase
            val cx = width * (blob.baseX + 0.10f * cos(t))
            val cy = height * (blob.baseY + 0.08f * sin(t * 0.85f))
            val radius = base * blob.radiusRatio

            paint.shader = RadialGradient(
                cx,
                cy,
                radius,
                intArrayOf(
                    withAlpha(blob.color, ALPHA_CENTER),
                    withAlpha(blob.color, ALPHA_MIDDLE),
                    Color.TRANSPARENT,
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
        paint.shader = null
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return Color.argb(
            alpha,
            Color.red(color),
            Color.green(color),
            Color.blue(color),
        )
    }

    private companion object {
        const val ALPHA_CENTER = 96
        const val ALPHA_MIDDLE = 52
    }
}
