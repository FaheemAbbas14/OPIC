package com.example.myapplication

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class OpicTextView1 @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 28f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    var timerText: String = "OPIC"
        set(value) {
            field = value
            invalidate()
        }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Gradient shader for text
        textPaint.shader = LinearGradient(
            0f, 0f, width.toFloat(), 0f,
            intArrayOf(Color.parseColor("#1CF3FF"), Color.parseColor("#FD2F55")),
            null,
            Shader.TileMode.CLAMP
        )

        val textY = (height / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2)
        canvas.drawText(timerText, width / 2f, textY, textPaint)
    }
}
