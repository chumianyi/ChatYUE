package com.deepseekold.chat

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes

object CompatToast {
    fun show(
        context: Context,
        @StringRes messageRes: Int,
        duration: Int = Toast.LENGTH_SHORT,
    ) {
        show(context, context.getString(messageRes), duration)
    }

    fun show(
        context: Context,
        message: String,
        duration: Int = Toast.LENGTH_SHORT,
    ) {
        val appContext = context.applicationContext
        val density = appContext.resources.displayMetrics.density
        val textView = TextView(appContext).apply {
            text = message
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(
                (18 * density).toInt(),
                (10 * density).toInt(),
                (18 * density).toInt(),
                (10 * density).toInt(),
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8 * density
                setColor(Color.rgb(45, 49, 57))
            }
        }
        Toast(appContext).apply {
            this.duration = duration
            view = textView
            setGravity(
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                0,
                (88 * density).toInt(),
            )
        }.show()
    }
}
