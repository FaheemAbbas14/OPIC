package com.example.myapplication

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.toColorInt

public class GlowingTimerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val borderPaints = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0f  // Thicker for clearer border
        maskFilter = BlurMaskFilter(2f, BlurMaskFilter.Blur.NORMAL) // Slight blur
    }

    private val backgroundPaints = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.TRANSPARENT
    }

    private val textPaints = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 35f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    //---

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        // The gradient will be set in onDraw
    }

    //s
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 35f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        color = Color.WHITE // Set text color to white
    }

    var timerText: String = "00:00:00"
        set(value) {
            field = value
            invalidate()
        }
    var isTimerRunning: Boolean = false

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = height / 2f
        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())

        if (isTimerRunning) {

            // Reduce intensity by adding transparency
            val leftColor = ColorUtils.setAlphaComponent("#1CF3FF".toColorInt(), 200)  // 70% opaque
            val rightColor = ColorUtils.setAlphaComponent("#FD2F55".toColorInt(), 200) // 70% opaque

            // New: Gradient background
            backgroundPaint.shader = LinearGradient(
                0f, 0f, // Start X, Y (left edge)
                width.toFloat(), 0f, // End X, Y (right edge)
                intArrayOf(
                    leftColor, // Start color (left)
                    rightColor  // End color (right)
                ),
                null, // Positions (null means evenly distributed)
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(rect, radius, radius, backgroundPaint)

            textPaint.shader = null // Remove shader if it was previously set

            // Centered white text
            val textY = (height / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2)
            canvas.drawText(timerText, width / 2f, textY, textPaint)
        } else {

            // Transparent background
            canvas.drawRoundRect(rect, radius, radius, backgroundPaints)

            // Glowing gradient border
            borderPaints.shader = LinearGradient(
                0f, 0f,            // Start at top
                0f, height.toFloat(),  // End at bottom (vertical)
                intArrayOf(
                    Color.parseColor("#FD2F55"),  // 🔵 Blue at top
                    Color.parseColor("#1CF3FF")   // 🌸 Pink at bottom
                ),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(rect, radius, radius, borderPaints)


            // Gradient text shader
            textPaints.shader = LinearGradient(
                0f, 0f, width.toFloat(), 0f,
                intArrayOf(
                    "#1CF3FF".toColorInt(),
                    "#FD2F55".toColorInt()
                ),
                null,
                Shader.TileMode.CLAMP
            )

            // Centered gradient text
            val textY = (height / 2f) - ((textPaints.descent() + textPaints.ascent()) / 2)
            canvas.drawText(timerText, width / 2f, textY, textPaints)
        }
    }
}
