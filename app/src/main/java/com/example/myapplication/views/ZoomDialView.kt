package com.opic3d.Spatial.trendingvideos.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class ZoomDialView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint().apply {
        color = Color.WHITE
        strokeWidth = 4f
        isAntiAlias = true
        style = Paint.Style.STROKE
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 32f
        textAlign = Paint.Align.CENTER
    }

    private var arcRect = RectF()
    private var centerX = 0f
    private var centerY = 0f
    private var radius = 0f
    private var zoomLevels = listOf(0.6f, 1.0f, 2.0f, 3.0f, 6.0f, 10.0f)
    var currentZoom = 1.0f
        private set

    var onZoomChanged: ((Float) -> Unit)? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        centerX = w.toFloat()
        centerY = h.toFloat() / 2
        radius = h / 2f - 40f
        arcRect.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw arc
        canvas.drawArc(arcRect, 180f, 180f, false, paint)

        // Draw ticks and labels
        for (i in zoomLevels.indices) {
            val angle = 180f + i * (180f / (zoomLevels.size - 1))
            val rad = Math.toRadians(angle.toDouble())
            val x = centerX + radius * cos(rad).toFloat()
            val y = centerY + radius * sin(rad).toFloat()
            canvas.drawCircle(x, y, 8f, paint)

            // Draw zoom level text
            val label = "${zoomLevels[i]}x"
            canvas.drawText(label, x, y - 20f, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_MOVE || event.action == MotionEvent.ACTION_DOWN) {
            val dx = event.x - centerX
            val dy = event.y - centerY
            val angle = Math.toDegrees(atan2(dy, dx).toDouble())
            val normalizedAngle = if (angle < 0) 360 + angle else angle

            if (normalizedAngle in 180.0..360.0) {
                val progress = (normalizedAngle - 180) / 180.0
                val zoom = zoomLevels.first() + progress.toFloat() * (zoomLevels.last() - zoomLevels.first())
                currentZoom = zoom
                onZoomChanged?.invoke(currentZoom)
                invalidate()
            }
        }
        return true
    }
}
