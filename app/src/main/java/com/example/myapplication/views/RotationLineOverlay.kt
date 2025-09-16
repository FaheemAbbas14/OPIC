package com.example.myapplication.views

import android.content.Context
import android.content.res.Configuration // ✅ NEW
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import androidx.annotation.ColorInt
import com.example.myapplication.R
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class RotationLineOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // Paints
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = dp(1f)
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    private val sidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = dp(1f)
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }

    // ------- Public knobs -------
    var response: Float = 0.18f
        set(value) { field = value.coerceIn(0f, 1f) }

    var deadbandDeg: Float = 1.0f
        set(value) { field = value.coerceAtLeast(0f) }

    var levelThresholdDeg: Float = 1.5f
        set(value) { field = value.coerceAtLeast(0f); invalidate() }

    /** If >0, fixed center-line length in px; else auto to gap between side lines minus insets. */
    var centerLineFixedLengthPx: Float = -1f
        set(value) { field = value; invalidate() }

    /** Length of each side line in px. */
    var sideLengthPx: Float = dp(40f)
        set(value) { field = value.coerceAtLeast(0f); invalidate() }

    /** Horizontal gap from view center to the center of each side line. */
    var sideGapPx: Float = dp(60f)
        set(value) { field = value.coerceAtLeast(0f); invalidate() }

    /** Stroke width for all lines (px). */
    var strokeWidthPx: Float
        get() = centerPaint.strokeWidth
        set(value) {
            val w = value.coerceAtLeast(1f)
            centerPaint.strokeWidth = w
            sidePaint.strokeWidth = w
            if (!centerInsetExplicitlySet) centerInsetPx = w
            invalidate()
        }

    /** Color when not level. */
    @ColorInt var normalColor: Int = Color.WHITE
        set(value) { field = value; invalidate() }

    /** Color when level. */
    @ColorInt var levelColor: Int = Color.YELLOW
        set(value) { field = value; invalidate() }

    /**
     * Extra clearance inside the side lines so the center line never overlaps them.
     * Default: equals current stroke width.
     */
    var centerInsetPx: Float = strokeWidthPx
        set(value) {
            field = value.coerceAtLeast(0f)
            centerInsetExplicitlySet = true
            invalidate()
        }
    private var centerInsetExplicitlySet = false

    // Smoothed state
    private var currentDeg = 0f
    private var targetDeg = 0f

    init {
        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.RotationLineOverlay)

            response        = a.getFloat(R.styleable.RotationLineOverlay_rl_response, response)
            deadbandDeg     = a.getFloat(R.styleable.RotationLineOverlay_rl_deadbandDeg, deadbandDeg)
            levelThresholdDeg = a.getFloat(
                R.styleable.RotationLineOverlay_rl_levelThresholdDeg, levelThresholdDeg
            )

            centerLineFixedLengthPx = a.getDimension(
                R.styleable.RotationLineOverlay_centerLineLength, centerLineFixedLengthPx
            )
            sideLengthPx = a.getDimension(
                R.styleable.RotationLineOverlay_sideLength, sideLengthPx
            )
            sideGapPx = a.getDimension(
                R.styleable.RotationLineOverlay_sideGap, sideGapPx
            )

            val stroke = a.getDimension(
                R.styleable.RotationLineOverlay_rl_strokeWidth, centerPaint.strokeWidth
            )
            strokeWidthPx = stroke

            if (a.hasValue(R.styleable.RotationLineOverlay_rl_centerInset)) {
                centerInsetPx = a.getDimension(
                    R.styleable.RotationLineOverlay_rl_centerInset, strokeWidthPx
                )
                centerInsetExplicitlySet = true
            } else {
                centerInsetPx = strokeWidthPx
                centerInsetExplicitlySet = false
            }

            normalColor = a.getColor(
                R.styleable.RotationLineOverlay_rl_normalColor, normalColor
            )
            levelColor = a.getColor(
                R.styleable.RotationLineOverlay_rl_levelColor, levelColor
            )
            a.recycle()
        }
    }

    private val orientationListener = object : OrientationEventListener(context) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) return

            val screenRotDeg = when (display?.rotation ?: Surface.ROTATION_0) {
                Surface.ROTATION_0   -> 0f
                Surface.ROTATION_90  -> 90f
                Surface.ROTATION_180 -> 180f
                Surface.ROTATION_270 -> 270f
                else -> 0f
            }
            // Portrait = horizontal (+90), Landscape = vertical (+0)
            val baseOffset = if (screenRotDeg == 90f || screenRotDeg == 270f) 0f else 90f
            val desired = normalize(orientation.toFloat() + screenRotDeg + baseOffset)

            val d = abs(shortestDelta(targetDeg, desired))
            if (d > deadbandDeg) {
                targetDeg = desired
                postInvalidateOnAnimation()
            }
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Smooth to target
        val delta = shortestDelta(currentDeg, targetDeg)
        if (abs(delta) > 0.01f) {
            currentDeg = normalize(currentDeg + delta * response)
            postInvalidateOnAnimation()
        } else {
            currentDeg = targetDeg
        }

        val contentW = width - paddingLeft - paddingRight
        val contentH = height - paddingTop - paddingBottom
        val cx = paddingLeft + contentW / 2f
        val cy = paddingTop + contentH / 2f

        val levelNow = isLevel(currentDeg)
        val color = if (levelNow) levelColor else normalColor
        centerPaint.color = color
        sidePaint.color   = color

        val rad = Math.toRadians(currentDeg.toDouble())
        val ux = cos(rad).toFloat()
        val uy = sin(rad).toFloat()

        // Fixed side-line centers
        val leftCX  = cx - sideGapPx
        val rightCX = cx + sideGapPx

        // Lengths
        val halfSide      = sideLengthPx / 2f
        val innerGap      = (rightCX - halfSide) - (leftCX + halfSide) // space between inner ends
        val availableGap  = (innerGap - (centerInsetPx * 2f)).coerceAtLeast(0f)

        // Center length: fixed or auto (respecting inset)
        val centerLen = if (centerLineFixedLengthPx > 0f) {
            centerLineFixedLengthPx.coerceAtMost(availableGap)
        } else {
            availableGap
        }

        val halfCenter  = centerLen / 2f
        val halfSideLen = halfSide

        // ✅ Only merge when level AND device is in landscape
        val shouldMerge = levelNow && isLandscapeNow()

        if (shouldMerge) {
            // MERGED: draw a single long line from left outer end to right outer end
            val mergedHalf = sideGapPx + halfSide + halfCenter + centerInsetPx
            val x1 = cx - ux * mergedHalf
            val y1 = cy - uy * mergedHalf
            val x2 = cx + ux * mergedHalf
            val y2 = cy + uy * mergedHalf
            canvas.drawLine(x1, y1, x2, y2, centerPaint)
        } else {
            // SPLIT: draw side lines first
            canvas.drawLine(
                leftCX - ux * halfSideLen,  cy - uy * halfSideLen,
                leftCX + ux * halfSideLen,  cy + uy * halfSideLen,
                sidePaint
            )
            canvas.drawLine(
                rightCX - ux * halfSideLen, cy - uy * halfSideLen,
                rightCX + ux * halfSideLen, cy + uy * halfSideLen,
                sidePaint
            )
            // Then center line strictly BETWEEN side lines
            val cX1 = cx - ux * halfCenter
            val cY1 = cy - uy * halfCenter
            val cX2 = cx + ux * halfCenter
            val cY2 = cy + uy * halfCenter
            canvas.drawLine(cX1, cY1, cX2, cY2, centerPaint)
        }
    }

    // ------- helpers -------
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

    // ✅ NEW: robust landscape check (falls back to width>height if undefined)
    private fun isLandscapeNow(): Boolean {
        val o = resources.configuration.orientation
        return if (o != Configuration.ORIENTATION_UNDEFINED) {
            o == Configuration.ORIENTATION_LANDSCAPE
        } else {
            width > height
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}
