package com.example.myapplication

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class GlowingTimerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0f  // Thicker for clearer border
        maskFilter = BlurMaskFilter(2f, BlurMaskFilter.Blur.NORMAL) // Slight blur
    }

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.TRANSPARENT
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 28f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    var timerText: String = "00:00:00"
        set(value) {
            field = value
            invalidate()
        }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val radius = height / 2f
        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())

        // Transparent background
        canvas.drawRoundRect(rect, radius, radius, backgroundPaint)

        // Glowing gradient border
        borderPaint.shader = LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            intArrayOf(
                Color.parseColor("#1CF3FF"),
                Color.parseColor("#FD2F55")
            ),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, radius, radius, borderPaint)

        // Gradient text shader
        textPaint.shader = LinearGradient(
            0f, 0f, width.toFloat(), 0f,
            intArrayOf(
                Color.parseColor("#1CF3FF"),
                Color.parseColor("#FD2F55")
            ),
            null,
            Shader.TileMode.CLAMP
        )

        // Centered gradient text
        val textY = (height / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2)
        canvas.drawText(timerText, width / 2f, textY, textPaint)
    }
}
