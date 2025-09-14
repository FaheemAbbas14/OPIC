package com.example.myapplication.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import com.example.myapplication.R
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class RotationLineOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    private val sidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }

    // Orientation smoothing
    private var rollDeg = 0f
    var smoothing: Float = 0.15f
    var levelThresholdDeg: Float = 2f

    // XML-configurable
    var centerLineFixedLengthPx: Float = -1f   // if <=0, auto to inner gap
    var sideLengthPx: Float = 40f              // length of each side line
    var sideGapPx: Float = 60f                 // horizontal distance from center to each side line center

    init {
        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.RotationLineOverlay)
            centerLineFixedLengthPx = a.getDimension(R.styleable.RotationLineOverlay_centerLineLength, -1f)
            sideGapPx = a.getDimension(R.styleable.RotationLineOverlay_sideGap, 60f)
            sideLengthPx = a.getDimension(R.styleable.RotationLineOverlay_sideLength, 40f)
            a.recycle()
        }
    }

    private val orientationListener = object : OrientationEventListener(context) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) return
            val delta = shortestDelta(rollDeg, orientation.toFloat())
            rollDeg = normalize(rollDeg + smoothing * delta)
            invalidate()
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
    private fun isLevel(angle: Float): Boolean {
        val mod = ((angle % 90f) + 90f) % 90f
        val dist = min(mod, 90f - mod)
        return dist <= levelThresholdDeg
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val contentW = width - paddingLeft - paddingRight
        val contentH = height - paddingTop - paddingBottom
        val cx = paddingLeft + contentW / 2f
        val cy = paddingTop + contentH / 2f

        // Portrait baseline = horizontal (+90), Landscape = vertical (+0)
        val screenRotDeg = when (display?.rotation ?: Surface.ROTATION_0) {
            Surface.ROTATION_0   -> 0f
            Surface.ROTATION_90  -> 90f
            Surface.ROTATION_180 -> 180f
            Surface.ROTATION_270 -> 270f
            else -> 0f
        }
        val baseOffset = if (screenRotDeg == 90f || screenRotDeg == 270f) 0f else 90f
        val drawAngle = normalize(rollDeg + screenRotDeg + baseOffset)

        val color = if (isLevel(drawAngle)) Color.YELLOW else Color.WHITE
        centerPaint.color = color
        sidePaint.color = color

        // Unit vector along the center-line angle
        val rad = Math.toRadians(drawAngle.toDouble())
        val ux = cos(rad).toFloat()
        val uy = sin(rad).toFloat()

        // ----- Center line (constant length) -----
        // Inner-gap between side lines when horizontal; used if no explicit center length set
        val halfSide = sideLengthPx / 2f
        val leftCX = cx - sideGapPx
        val rightCX = cx + sideGapPx
        val innerGap = (rightCX - halfSide) - (leftCX + halfSide)
        val centerLen = if (centerLineFixedLengthPx > 0f) centerLineFixedLengthPx else innerGap

        val halfCenter = centerLen / 2f
        val cX1 = cx - ux * halfCenter
        val cY1 = cy - uy * halfCenter
        val cX2 = cx + ux * halfCenter
        val cY2 = cy + uy * halfCenter
        canvas.drawLine(cX1, cY1, cX2, cY2, centerPaint)

        // ----- Side lines: FIXED positions, but rotate with the center line -----
        // Keep their centers fixed horizontally from screen center (don’t move),
        // but their angle follows the center line (use ux,uy direction).
        val halfSideLen = sideLengthPx / 2f

        // Left side line (center at leftCX,cy) oriented with (ux,uy)
        canvas.drawLine(
            leftCX - ux * halfSideLen, cy - uy * halfSideLen,
            leftCX + ux * halfSideLen, cy + uy * halfSideLen,
            sidePaint
        )
        // Right side line (center at rightCX,cy) oriented with (ux,uy)
        canvas.drawLine(
            rightCX - ux * halfSideLen, cy - uy * halfSideLen,
            rightCX + ux * halfSideLen, cy + uy * halfSideLen,
            sidePaint
        )
    }
}