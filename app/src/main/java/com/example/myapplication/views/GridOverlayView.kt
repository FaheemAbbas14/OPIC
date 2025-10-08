package com.opic3d.Spatial.trendingvideos.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class GridOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint().apply {
        color = Color.WHITE
        strokeWidth = 1f
        alpha = 120
        style = Paint.Style.STROKE
    }

    override fun onDraw(canvas: Canvas) {
        val thirdWidth = width / 3f
        val thirdHeight = height / 3f

        // Vertical lines
        canvas.drawLine(thirdWidth, 0f, thirdWidth, height.toFloat(), paint)
        canvas.drawLine(2 * thirdWidth, 0f, 2 * thirdWidth, height.toFloat(), paint)

        // Horizontal lines
        canvas.drawLine(0f, thirdHeight, width.toFloat(), thirdHeight, paint)
        canvas.drawLine(0f, 2 * thirdHeight, width.toFloat(), 2 * thirdHeight, paint)
    }
}
