package com.chatyue.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/**
 * 近 7 天消息柱状图（自绘，不引第三方图表库）。
 *
 * 每天 stacked 两根柱子：下半截为用户消息，上半截为助手消息；
 * 有数据时在柱顶显示当日总数，底部显示「周几」标签。
 */
class WeeklyBarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val userPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ds_blue)
    }
    private val assistantPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ds_blue_soft)
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ds_divider)
        strokeWidth = resources.displayMetrics.density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ds_text_tertiary)
        textSize = 10f * resources.displayMetrics.density
        textAlign = Paint.Align.CENTER
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ds_text_secondary)
        textSize = 10f * resources.displayMetrics.density
        textAlign = Paint.Align.CENTER
    }

    private var bars: List<DailyCount> = emptyList()
    private var maxTotal: Int = 0

    /** 有数据柱子的最小高度（dp），避免 1 条消息时柱子看不见。 */
    private val minBarHeight = 3f * resources.displayMetrics.density

    /** 填入一周数据；空数据时显示占位文案。 */
    fun submit(bars: List<DailyCount>) {
        this.bars = bars
        this.maxTotal = bars.maxOfOrNull { it.totalCount } ?: 0
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val labelHeight = labelPaint.textSize * 2
        val chartBottom = height - labelHeight
        val chartTop = valuePaint.textSize * 1.5f
        val usableHeight = chartBottom - chartTop

        // 基线：把图表区与标签区隔开。
        canvas.drawLine(0f, chartBottom, width.toFloat(), chartBottom, axisPaint)

        if (bars.isEmpty()) {
            canvas.drawText(
                context.getString(R.string.stats_chart_empty),
                width / 2f,
                (chartTop + chartBottom) / 2f,
                labelPaint,
            )
            return
        }

        val slot = width.toFloat() / bars.size
        val barWidth = slot * BAR_WIDTH_RATIO
        bars.forEachIndexed { index, bar ->
            val centerX = slot * index + slot / 2f
            val left = centerX - barWidth / 2f
            val right = centerX + barWidth / 2f

            // 没有数据的日子画一根贴地的占位柱，保持视觉连续。
            val totalHeight = if (maxTotal <= 0) {
                0f
            } else {
                usableHeight * bar.totalCount / maxTotal
            }
            val drawHeight = maxOf(totalHeight, if (bar.totalCount > 0) minBarHeight else 0f)
            val userRatio = if (bar.totalCount > 0) {
                bar.userCount.toFloat() / bar.totalCount
            } else {
                0f
            }
            val userHeight = drawHeight * userRatio
            val assistantHeight = drawHeight - userHeight

            if (userHeight > 0f) {
                canvas.drawRect(
                    left,
                    chartBottom - userHeight,
                    right,
                    chartBottom,
                    userPaint,
                )
            }
            if (assistantHeight > 0f) {
                canvas.drawRect(
                    left,
                    chartBottom - userHeight - assistantHeight,
                    right,
                    chartBottom - userHeight,
                    assistantPaint,
                )
            }
            if (bar.totalCount > 0) {
                canvas.drawText(
                    bar.totalCount.toString(),
                    centerX,
                    chartBottom - drawHeight - valuePaint.textSize * 0.4f,
                    valuePaint,
                )
            }
            canvas.drawText(bar.label, centerX, height - labelPaint.textSize * 0.6f, labelPaint)
        }
    }

    private companion object {
        /** 柱宽占单列宽度的比例。 */
        const val BAR_WIDTH_RATIO = 0.42f
    }
}
