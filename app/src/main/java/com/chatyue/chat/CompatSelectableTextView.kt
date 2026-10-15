package com.chatyue.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.ColorDrawable
import android.text.Layout
import android.os.Build
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

class CompatSelectableTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle,
) : TextView(context, attrs, defStyleAttr) {

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var selectionStart = -1
    private var selectionEnd = -1
    private var draggingHandle = Handle.NONE
    private var copyPopup: PopupWindow? = null
    private var preserveSelectionOnDismiss = false
    private val useNativeSelection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M

    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 77, 107, 254)
        style = Paint.Style.FILL
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(77, 107, 254)
        style = Paint.Style.FILL
    }

    init {
        if (useNativeSelection) {
            setTextIsSelectable(true)
        } else {
            setTextIsSelectable(false)
            setOnLongClickListener {
                selectWordAt(lastTouchX, lastTouchY)
                showCopyMenu()
                true
            }
        }
    }

    override fun onTextChanged(
        text: CharSequence?,
        start: Int,
        before: Int,
        count: Int,
    ) {
        selectionStart = -1
        selectionEnd = -1
        super.onTextChanged(text, start, before, count)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (useNativeSelection) return super.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                draggingHandle = handleAt(event.x, event.y)
                if (draggingHandle != Handle.NONE) {
                    preserveSelectionOnDismiss = true
                    copyPopup?.dismiss()
                    return true
                }
                if (hasCompatSelection()) {
                    clearSelection()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                lastTouchX = event.x
                lastTouchY = event.y
                if (draggingHandle != Handle.NONE) {
                    moveHandle(event.x, event.y)
                    return true
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (draggingHandle != Handle.NONE) {
                    draggingHandle = Handle.NONE
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        showCopyMenu()
                    }
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        if (!useNativeSelection) drawSelection(canvas)
        super.onDraw(canvas)
        if (!useNativeSelection) drawHandles(canvas)
    }

    override fun onDetachedFromWindow() {
        copyPopup?.dismiss()
        copyPopup = null
        super.onDetachedFromWindow()
    }

    private fun selectWordAt(x: Float, y: Float) {
        val content = text ?: return
        if (content.isEmpty()) return
        val offset = offsetAt(x, y)
        val start = offset.coerceIn(0, content.length - 1)
        var wordStart = start
        var wordEnd = start
        while (wordStart > 0 && !isWordBoundary(content[wordStart - 1])) {
            wordStart--
        }
        while (wordEnd < content.length && !isWordBoundary(content[wordEnd])) {
            wordEnd++
        }
        setSelectionRange(wordStart, wordEnd)
    }

    private fun setSelectionRange(start: Int, end: Int) {
        val contentLength = text?.length ?: 0
        if (contentLength == 0) {
            selectionStart = -1
            selectionEnd = -1
        } else {
            selectionStart = start.coerceIn(0, contentLength)
            selectionEnd = end.coerceIn(0, contentLength)
        }
        invalidate()
    }

    private fun offsetAt(x: Float, y: Float): Int {
        val textLayout: Layout = layout ?: return 0
        val line = textLayout.getLineForVertical(
            (y - compoundPaddingTop + scrollY).toInt().coerceAtLeast(0),
        )
        return textLayout.getOffsetForHorizontal(
            line,
            (x - compoundPaddingLeft + scrollX).coerceAtLeast(0f),
        ).coerceIn(0, text?.length ?: 0)
    }

    private fun moveHandle(x: Float, y: Float) {
        val offset = offsetAt(x, y)
        when (draggingHandle) {
            Handle.START -> {
                selectionStart = offset.coerceIn(0, selectionEnd.coerceAtLeast(0))
            }

            Handle.END -> {
                selectionEnd = offset.coerceIn(selectionStart.coerceAtLeast(0), text?.length ?: 0)
            }

            Handle.NONE -> Unit
        }
        invalidate()
    }

    private fun drawSelection(canvas: Canvas) {
        if (!hasCompatSelection()) return
        val textLayout = layout ?: return
        val path = Path()
        textLayout.getSelectionPath(selectionStart, selectionEnd, path)
        canvas.save()
        canvas.translate(
            compoundPaddingLeft - scrollX.toFloat(),
            compoundPaddingTop - scrollY.toFloat(),
        )
        canvas.drawPath(path, selectionPaint)
        canvas.restore()
    }

    private fun drawHandles(canvas: Canvas) {
        if (!hasCompatSelection()) return
        drawHandle(canvas, selectionStart)
        drawHandle(canvas, selectionEnd)
    }

    private fun drawHandle(canvas: Canvas, offset: Int) {
        val textLayout = layout ?: return
        val safeOffset = offset.coerceIn(0, text?.length ?: 0)
        val line = textLayout.getLineForOffset(safeOffset)
        val x = textLayout.getPrimaryHorizontal(safeOffset) +
            compoundPaddingLeft - scrollX
        val y = textLayout.getLineBottom(line).toFloat() +
            compoundPaddingTop - scrollY
        val radius = dpToPx(6).toFloat()
        val stemTop = y - dpToPx(20)
        canvas.drawRect(x - dpToPx(2), stemTop, x + dpToPx(2), y, handlePaint)
        canvas.drawCircle(x, y, radius, handlePaint)
    }

    private fun handleAt(x: Float, y: Float): Handle {
        if (!hasCompatSelection()) return Handle.NONE
        val startPoint = handlePoint(selectionStart)
        val endPoint = handlePoint(selectionEnd)
        val threshold = dpToPx(32).toFloat()
        return when {
            distanceSquared(x, y, startPoint.first, startPoint.second) <= threshold * threshold ->
                Handle.START
            distanceSquared(x, y, endPoint.first, endPoint.second) <= threshold * threshold ->
                Handle.END
            else -> Handle.NONE
        }
    }

    private fun handlePoint(offset: Int): Pair<Float, Float> {
        val textLayout = layout ?: return 0f to 0f
        val safeOffset = offset.coerceIn(0, text?.length ?: 0)
        val line = textLayout.getLineForOffset(safeOffset)
        return (
            textLayout.getPrimaryHorizontal(safeOffset) +
                compoundPaddingLeft - scrollX
            ) to (
            textLayout.getLineBottom(line).toFloat() +
                compoundPaddingTop - scrollY
            )
    }

    private fun showCopyMenu() {
        val content = text?.toString().orEmpty()
        if (content.isEmpty()) return

        copyPopup?.dismiss()
        val density = resources.displayMetrics.density
        val menu = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = ColorDrawable(Color.WHITE)
            elevation = 8 * density
        }
        val popup = PopupWindow(
            menu,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            false,
        ).apply {
            setBackgroundDrawable(ColorDrawable(Color.WHITE))
            isOutsideTouchable = true
            setTouchInterceptor { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    preserveSelectionOnDismiss = true
                    dismiss()
                    false
                } else {
                    false
                }
            }
            setOnDismissListener {
                copyPopup = null
                if (!preserveSelectionOnDismiss) {
                    clearSelection()
                }
                preserveSelectionOnDismiss = false
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                elevation = 8 * density
            }
        }
        addMenuItem(menu, R.string.copy) {
            copySelectedText()
            popup.dismiss()
        }
        addMenuItem(menu, R.string.copy_all) {
            copyText(content)
            popup.dismiss()
        }
        copyPopup = popup

        menu.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val location = IntArray(2)
        getLocationOnScreen(location)
        val screenWidth = resources.displayMetrics.widthPixels
        val anchorX = location[0] + lastTouchX.toInt()
        val anchorY = location[1] + lastTouchY.toInt()
        val x = (anchorX - menu.measuredWidth / 2)
            .coerceIn(dpToPx(8), screenWidth - menu.measuredWidth - dpToPx(8))
        val y = (anchorY - menu.measuredHeight - dpToPx(10))
            .coerceAtLeast(dpToPx(8))
        popup.showAtLocation(rootView, Gravity.TOP or Gravity.START, x, y)
    }

    private fun addMenuItem(
        menu: LinearLayout,
        textRes: Int,
        onClick: () -> Unit,
    ) {
        menu.addView(
            TextView(context).apply {
                setText(textRes)
                setTextColor(Color.rgb(32, 36, 43))
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(
                    dpToPx(16),
                    dpToPx(12),
                    dpToPx(16),
                    dpToPx(12),
                )
                setOnClickListener { onClick() }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    private fun copySelectedText() {
        val currentText = text?.toString().orEmpty()
        if (hasCompatSelection()) {
            copyText(currentText.substring(selectionStart, selectionEnd))
        } else {
            copyText(currentText)
        }
    }

    private fun copyText(value: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
            as? ClipboardManager
            ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("message", value))
        clearSelection()
        CompatToast.show(context, R.string.copied)
    }

    private fun clearSelection() {
        selectionStart = -1
        selectionEnd = -1
        invalidate()
    }

    private fun hasCompatSelection(): Boolean {
        return selectionStart >= 0 &&
            selectionEnd > selectionStart &&
            selectionEnd <= (text?.length ?: 0)
    }

    private fun isWordBoundary(character: Char): Boolean {
        return character.isWhitespace() || character in ",.!?;:，。！？；：、()[]{}<>\"'`"
    }

    private fun distanceSquared(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
    ): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return dx * dx + dy * dy
    }

    private fun dpToPx(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private enum class Handle {
        NONE,
        START,
        END,
    }
}
