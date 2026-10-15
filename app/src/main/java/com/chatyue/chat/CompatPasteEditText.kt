package com.chatyue.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.text.Editable
import android.text.Layout
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.appcompat.widget.AppCompatEditText

/**
 * API 21-22's TextView action popup can crash while creating its clipboard
 * menu. Keep the old platform from entering that path and provide selection
 * handles and clipboard actions directly from the app.
 */
class CompatPasteEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle,
) : AppCompatEditText(context, attrs, defStyleAttr) {

    private val useNativeSelection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var compatSelectionStart = -1
    private var compatSelectionEnd = -1
    private var draggingHandle = Handle.NONE
    private var dragTouchOffsetX = 0f
    private var dragTouchOffsetY = 0f
    private var pendingDragX = 0f
    private var pendingDragY = 0f
    private var dragFramePosted = false
    private var actionPopup: PopupWindow? = null
    private var preserveSelectionOnDismiss = false
    private val dragFrameRunnable = Runnable {
        dragFramePosted = false
        if (draggingHandle != Handle.NONE) {
            moveHandle(pendingDragX, pendingDragY)
        }
    }

    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 77, 107, 254)
        style = Paint.Style.FILL
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(77, 107, 254)
        style = Paint.Style.FILL
    }

    init {
        if (!useNativeSelection) {
            customSelectionActionModeCallback = RejectActionModeCallback
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(
                    value: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) = Unit

                override fun onTextChanged(
                    value: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int,
                ) = Unit

                override fun afterTextChanged(value: Editable?) {
                    clearCompatSelection()
                }
            })
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (useNativeSelection) return super.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                lastRawX = event.rawX
                lastRawY = event.rawY
                draggingHandle = handleAt(event.x, event.y)
                if (draggingHandle != Handle.NONE) {
                    val handlePoint = when (draggingHandle) {
                        Handle.START -> handlePoint(compatSelectionStart)
                        Handle.END -> handlePoint(compatSelectionEnd)
                        Handle.NONE -> 0f to 0f
                    }
                    dragTouchOffsetX = event.x - handlePoint.first
                    dragTouchOffsetY = event.y - handlePoint.second
                    preserveSelectionOnDismiss = true
                    actionPopup?.dismiss()
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                if (hasCompatSelection()) {
                    actionPopup?.dismiss()
                    clearCompatSelection()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                lastTouchX = event.x
                lastTouchY = event.y
                lastRawX = event.rawX
                lastRawY = event.rawY
                if (draggingHandle != Handle.NONE) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    pendingDragX = event.x - dragTouchOffsetX
                    pendingDragY = event.y - dragTouchOffsetY
                    if (!dragFramePosted) {
                        dragFramePosted = true
                        postOnAnimation(dragFrameRunnable)
                    }
                    return true
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (draggingHandle != Handle.NONE) {
                    removeCallbacks(dragFrameRunnable)
                    dragFramePosted = false
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        moveHandle(
                            event.x - dragTouchOffsetX,
                            event.y - dragTouchOffsetY,
                        )
                    }
                    draggingHandle = Handle.NONE
                    dragTouchOffsetX = 0f
                    dragTouchOffsetY = 0f
                    parent?.requestDisallowInterceptTouchEvent(false)
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        showActionMenu()
                    }
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performLongClick(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return super.performLongClick()
        }
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (!text.isNullOrEmpty()) {
            selectWordAt(lastTouchX, lastTouchY)
        }
        showActionMenu()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        if (!useNativeSelection) drawSelection(canvas)
        super.onDraw(canvas)
        if (!useNativeSelection) drawHandles(canvas)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(dragFrameRunnable)
        dragFramePosted = false
        actionPopup?.dismiss()
        actionPopup = null
        super.onDetachedFromWindow()
    }

    private fun selectWordAt(x: Float, y: Float) {
        val content = text?.toString().orEmpty()
        if (content.isEmpty()) return
        val offset = offsetAt(x, y).coerceIn(0, content.length - 1)
        var start = offset
        var end = offset + 1
        if (isWordCharacter(content[offset])) {
            while (start > 0 && isWordCharacter(content[start - 1])) {
                start--
            }
            while (end < content.length && isWordCharacter(content[end])) {
                end++
            }
        }
        setCompatSelection(start, end)
    }

    private fun setCompatSelection(start: Int, end: Int) {
        val contentLength = text?.length ?: 0
        if (contentLength == 0) {
            clearCompatSelection()
            return
        }
        compatSelectionStart = start.coerceIn(0, contentLength)
        compatSelectionEnd = end.coerceIn(compatSelectionStart, contentLength)
        isCursorVisible = false
        hideNativeInsertionHandle()
        invalidate()
    }

    private fun offsetAt(x: Float, y: Float): Int {
        val textLayout: Layout = layout ?: return selectionStart.coerceAtLeast(0)
        val vertical = (y - compoundPaddingTop + scrollY)
            .toInt()
            .coerceIn(0, textLayout.height.coerceAtLeast(0))
        val line = textLayout.getLineForVertical(vertical)
        return textLayout.getOffsetForHorizontal(
            line,
            (x - compoundPaddingLeft + scrollX).coerceAtLeast(0f),
        ).coerceIn(0, text?.length ?: 0)
    }

    private fun moveHandle(x: Float, y: Float) {
        // The visual handle sits below the glyph. Sample slightly above it so
        // a handle on a line boundary does not alternate between two lines.
        val offset = offsetAt(x, y - dpToPx(8))
        val contentLength = text?.length ?: 0
        if (contentLength == 0) return

        when (draggingHandle) {
            Handle.START -> {
                when {
                    offset < compatSelectionEnd -> {
                        compatSelectionStart = offset.coerceAtLeast(0)
                    }

                    offset > compatSelectionEnd -> {
                        compatSelectionStart = compatSelectionEnd
                        compatSelectionEnd = offset.coerceAtMost(contentLength)
                        draggingHandle = Handle.END
                    }

                    else -> {
                        compatSelectionStart =
                            (compatSelectionEnd - 1).coerceAtLeast(0)
                    }
                }
            }

            Handle.END -> {
                when {
                    offset > compatSelectionStart -> {
                        compatSelectionEnd = offset.coerceAtMost(contentLength)
                    }

                    offset < compatSelectionStart -> {
                        compatSelectionEnd = compatSelectionStart
                        compatSelectionStart = offset.coerceAtLeast(0)
                        draggingHandle = Handle.START
                    }

                    else -> {
                        compatSelectionEnd =
                            (compatSelectionStart + 1).coerceAtMost(contentLength)
                    }
                }
            }

            Handle.NONE -> Unit
        }
        invalidate()
    }

    private fun drawSelection(canvas: Canvas) {
        if (!hasCompatSelection()) return
        val textLayout = layout ?: return
        val path = Path()
        textLayout.getSelectionPath(compatSelectionStart, compatSelectionEnd, path)
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
        drawHandle(canvas, compatSelectionStart)
        drawHandle(canvas, compatSelectionEnd)
    }

    private fun drawHandle(canvas: Canvas, offset: Int) {
        val point = handlePoint(offset)
        val radius = dpToPx(9).toFloat()
        val stemTop = point.second - dpToPx(20)
        canvas.drawRect(
            point.first - dpToPx(2),
            stemTop,
            point.first + dpToPx(2),
            point.second,
            handlePaint,
        )
        canvas.drawCircle(point.first, point.second, radius, handlePaint)
    }

    private fun handleAt(x: Float, y: Float): Handle {
        if (!hasCompatSelection()) return Handle.NONE
        val startPoint = handlePoint(compatSelectionStart)
        val endPoint = handlePoint(compatSelectionEnd)
        val horizontalRadius = dpToPx(28).toFloat()
        val verticalAbove = dpToPx(32).toFloat()
        val verticalBelow = dpToPx(52).toFloat()
        val startHit = isInsideHandleTouchArea(
            x,
            y,
            startPoint,
            horizontalRadius,
            verticalAbove,
            verticalBelow,
        )
        val endHit = isInsideHandleTouchArea(
            x,
            y,
            endPoint,
            horizontalRadius,
            verticalAbove,
            verticalBelow,
        )
        if (!startHit && !endHit) {
            return Handle.NONE
        }
        if (startHit && !endHit) return Handle.START
        if (endHit && !startHit) return Handle.END

        val startDistance = distanceSquared(x, y, startPoint.first, startPoint.second)
        val endDistance = distanceSquared(x, y, endPoint.first, endPoint.second)
        return if (startDistance <= endDistance) Handle.START else Handle.END
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

    private fun showActionMenu() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
            as? ClipboardManager
            ?: return
        val content = text?.toString().orEmpty()
        if (content.isEmpty() && !clipboard.hasPrimaryClip()) return

        if (actionPopup != null) {
            preserveSelectionOnDismiss = true
            actionPopup?.dismiss()
        }
        val density = resources.displayMetrics.density
        val menu = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = ColorDrawable(Color.rgb(45, 49, 57))
            elevation = 8 * density
        }
        val popup = PopupWindow(
            menu,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            false,
        ).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            setOnDismissListener {
                actionPopup = null
                if (!preserveSelectionOnDismiss) {
                    clearCompatSelection()
                }
                preserveSelectionOnDismiss = false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = 8 * density
            }
        }
        if (hasCompatSelection()) {
            addMenuItem(menu, R.string.copy) {
                copySelectedText()
                popup.dismiss()
            }
        }
        if (clipboard.hasPrimaryClip()) {
            addMenuItem(menu, R.string.paste) {
                pasteClipboardText(clipboard)
                popup.dismiss()
            }
        }
        if (content.isNotEmpty()) {
            addMenuItem(menu, R.string.copy_all) {
                copyText(content)
                popup.dismiss()
            }
        }
        actionPopup = popup

        menu.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val screenWidth = resources.displayMetrics.widthPixels
        val minX = dpToPx(8)
        val maxX = (screenWidth - menu.measuredWidth - minX).coerceAtLeast(minX)
        val x = (lastRawX - menu.measuredWidth / 2)
            .toInt()
            .coerceIn(minX, maxX)
        val y = (lastRawY - menu.measuredHeight - dpToPx(12))
            .toInt()
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
                setTextColor(Color.WHITE)
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
        if (!hasCompatSelection()) return
        val content = text?.toString().orEmpty()
        val end = compatSelectionEnd
        copyText(content.substring(compatSelectionStart, compatSelectionEnd))
        setSelection(end.coerceAtMost(text?.length ?: 0))
    }

    private fun copyText(value: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
            as? ClipboardManager
            ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("text", value))
        clearCompatSelection()
        CompatToast.show(context, R.string.copied)
    }

    private fun pasteClipboardText(clipboard: ClipboardManager) {
        val pastedText = clipboard.primaryClip
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            .orEmpty()
        if (pastedText.isEmpty()) return

        val start = if (hasCompatSelection()) {
            compatSelectionStart
        } else {
            minOf(selectionStart, selectionEnd).coerceAtLeast(0)
        }
        val end = if (hasCompatSelection()) {
            compatSelectionEnd
        } else {
            maxOf(selectionStart, selectionEnd).coerceAtLeast(start)
        }
        text?.replace(start, end, pastedText)
        setSelection((start + pastedText.length).coerceAtMost(text?.length ?: 0))
        clearCompatSelection()
    }

    private fun clearCompatSelection() {
        removeCallbacks(dragFrameRunnable)
        dragFramePosted = false
        draggingHandle = Handle.NONE
        compatSelectionStart = -1
        compatSelectionEnd = -1
        if (!useNativeSelection) {
            isCursorVisible = true
        }
        invalidate()
    }

    private fun hasCompatSelection(): Boolean {
        return compatSelectionStart >= 0 &&
            compatSelectionEnd > compatSelectionStart &&
            compatSelectionEnd <= (text?.length ?: 0)
    }

    private fun isWordCharacter(character: Char): Boolean {
        return character.isLetterOrDigit() || character == '_'
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

    private fun isInsideHandleTouchArea(
        x: Float,
        y: Float,
        point: Pair<Float, Float>,
        horizontalRadius: Float,
        verticalAbove: Float,
        verticalBelow: Float,
    ): Boolean {
        return x >= point.first - horizontalRadius &&
            x <= point.first + horizontalRadius &&
            y >= point.second - verticalAbove &&
            y <= point.second + verticalBelow
    }

    private fun hideNativeInsertionHandle() {
        if (useNativeSelection) return
        runCatching {
            val editorField = TextView::class.java.getDeclaredField("mEditor").apply {
                isAccessible = true
            }
            val editor = editorField.get(this) ?: return@runCatching
            val controllerField = editor.javaClass
                .getDeclaredField("mInsertionPointCursorController")
                .apply { isAccessible = true }
            val controller = controllerField.get(editor) ?: return@runCatching
            controller.javaClass.getDeclaredMethod("hide").apply {
                isAccessible = true
            }.invoke(controller)
        }
    }

    private fun dpToPx(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private object RejectActionModeCallback : android.view.ActionMode.Callback {
        override fun onCreateActionMode(
            mode: android.view.ActionMode,
            menu: android.view.Menu,
        ): Boolean = false

        override fun onPrepareActionMode(
            mode: android.view.ActionMode,
            menu: android.view.Menu,
        ): Boolean = false

        override fun onActionItemClicked(
            mode: android.view.ActionMode,
            item: android.view.MenuItem,
        ): Boolean = false

        override fun onDestroyActionMode(mode: android.view.ActionMode) = Unit
    }

    private enum class Handle {
        NONE,
        START,
        END,
    }
}
