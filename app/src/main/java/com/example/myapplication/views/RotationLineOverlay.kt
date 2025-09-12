package com.example.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.OrientationEventListener
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class RotationLineOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE           // default: not level = white
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }

    /** Current smoothed angle in degrees (0..360). */
    private var angleDeg = 0f

    /** Smoothing factor in [0,1]. Smaller = smoother (more lag). */
    var smoothing: Float = 0.15f

    /** Within this many degrees of a multiple of 90°, we consider it "level". */
    var levelThresholdDeg: Float = 2.0f

    private val orientationListener = object : OrientationEventListener(context) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) return
            smoothTo(orientation.toFloat())
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        orientationListener.disable()
    }

    private fun smoothTo(target: Float) {
        val delta = shortestDelta(angleDeg, target)
        angleDeg = normalize(angleDeg + smoothing * delta)
        invalidate()
    }

    private fun shortestDelta(from: Float, to: Float): Float {
        var d = (to - from) % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    private fun normalize(a: Float): Float {
        var x = a % 360f
        if (x < 0f) x += 360f
        return x
    }

    /** true when angle is within [levelThresholdDeg] of any 0/90/180/270° */
    private fun isLevel(angle: Float): Boolean {
        val mod = ((angle % 90f) + 90f) % 90f
        val distToNearestRightAngle = min(mod, 90f - mod)
        return distToNearestRightAngle <= levelThresholdDeg
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // set color based on "level" state
        paint.color = if (isLevel(angleDeg)) Color.YELLOW else Color.WHITE

        val contentW = width - paddingLeft - paddingRight
        val contentH = height - paddingTop - paddingBottom
        val cx = paddingLeft + contentW / 2f
        val cy = paddingTop + contentH / 2f
        val len = min(contentW, contentH) / 2f - paint.strokeWidth

        val drawAngle = (angleDeg + 90f) % 360f     // or use -90f if you prefer
        val rad = Math.toRadians(drawAngle.toDouble())
        val dx = (cos(rad) * len).toFloat()
        val dy = (sin(rad) * len).toFloat()

        canvas.drawLine(cx - dx, cy - dy, cx + dx, cy + dy, paint)
    }
}
