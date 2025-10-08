package com.opic3d.Spatial.trendingvideos

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import androidx.core.content.res.getDimensionOrThrow
import androidx.core.content.res.getFloatOrThrow
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round

class FocusRulerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var onFocusChanged: ((Float) -> Unit)? = null

    var minZoom = 0.0f
    var maxZoom = 1.0f
    var step = 0.02f
    private val selectionThreshold = step / 2

    // Major tick labels
    private val zoomLevels = listOf(
        0.00f, 0.10f, 0.20f, 0.30f, 0.40f, 0.50f,
        0.60f, 0.70f, 0.80f, 0.90f, 1.00f
    )

    // Internal continuous value used while dragging
    private var rawValue = 0.0f

    /** Current value (0f..1f) */
    var focusValue = 0.0f
        set(v) {
            val nv = v.coerceIn(minZoom, maxZoom)
            if (field != nv) {
                field = nv
                // Keep drag baseline aligned with the displayed value
                rawValue = nv
                invalidate()
            }
        }

    // Styling
    private var tickSpacingPx = dp(8f)   // spacing between 0.02 ticks
    private var textSizePx = sp(11f)
    private var pointerRadiusPx = dp(6f)

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        strokeWidth = dp(2f)
    }
    private val faintTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66444444")
        strokeWidth = dp(2f)
    }
    private val majorTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        textSize = textSizePx
    }
    private val currentTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = textSizePx * 1.1f
        style = Paint.Style.FILL
    }
    private val pointerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF2D55")
        style = Paint.Style.FILL
    }
    private val accentBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(4f)
        strokeCap = Paint.Cap.ROUND
    }

    private val scroller = OverScroller(context)

    init {
        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.ZoomRulerView)
            if (a.hasValue(R.styleable.ZoomRulerView_zr_minZoom))
                minZoom = a.getFloatOrThrow(R.styleable.ZoomRulerView_zr_minZoom)
            if (a.hasValue(R.styleable.ZoomRulerView_zr_maxZoom))
                maxZoom = a.getFloatOrThrow(R.styleable.ZoomRulerView_zr_maxZoom)
            if (a.hasValue(R.styleable.ZoomRulerView_zr_step))
                step = a.getFloatOrThrow(R.styleable.ZoomRulerView_zr_step)
            if (a.hasValue(R.styleable.ZoomRulerView_zr_tickSpacing))
                tickSpacingPx = a.getDimensionOrThrow(R.styleable.ZoomRulerView_zr_tickSpacing)
            if (a.hasValue(R.styleable.ZoomRulerView_zr_textSize))
                textSizePx = a.getDimensionOrThrow(R.styleable.ZoomRulerView_zr_textSize)
            if (a.hasValue(R.styleable.ZoomRulerView_zr_pointerRadius))
                pointerRadiusPx = a.getDimensionOrThrow(R.styleable.ZoomRulerView_zr_pointerRadius)
            a.recycle()
        }

        // Gradient for current value
        val shader = LinearGradient(
            0f, 0f, dp(68f), 0f,
            intArrayOf(Color.parseColor("#10BCE9"), Color.parseColor("#D81B60")),
            null, Shader.TileMode.CLAMP
        )
        currentTextPaint.shader = shader
        accentBarPaint.shader = shader

        setWillNotDraw(false)
        isClickable = true
    }

    /** Set without invoking callbacks; keeps drag baseline in sync. */
    fun setValueSilently(v: Float) {
        val nv = v.coerceIn(minZoom, maxZoom)
        focusValue = nv
        rawValue = nv
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val minW = dp(80f).toInt()
        val w = resolveSize(minW, widthMeasureSpec)
        val h = resolveSize(suggestedMinimumHeight, heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    private var lastY = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastY = event.y
                rawValue = focusValue          // <<< start drag from current value
                scroller.forceFinished(true)
                return performClick()
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = event.y - lastY
                lastY = event.y
                val delta = dy / tickSpacingPx * step
                rawValue = (rawValue + delta).coerceIn(minZoom, maxZoom)

                // Snap only for display / callback
                val snapped = round(rawValue / step) * step
                val newValue = BigDecimal(snapped.toDouble())
                    .setScale(2, RoundingMode.HALF_UP)
                    .toFloat()

                if (newValue != focusValue) {
                    // Update display value; keep rawValue as the continuous accumulator
                    focusValue = newValue
                    onFocusChanged?.invoke(focusValue)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val snapped = round(rawValue / step) * step
                val finalValue = (snapped * 100f).toInt() / 100f.toFloat()
                focusValue = finalValue
                rawValue = finalValue           // <<< keep internal state aligned
                onFocusChanged?.invoke(focusValue)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            val v = (scroller.currY.toFloat() / tickSpacingPx * step)
                .coerceIn(minZoom, maxZoom)
            focusValue = v
            rawValue = v
            postInvalidateOnAnimation()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val cxPointer = dp(12f)
        val cyPointer = h / 2f

        // Draw pointer
        canvas.drawCircle(cxPointer, cyPointer, pointerRadiusPx, pointerPaint)

        // Current value label
        val label = String.format("%.2fx", focusValue)
        val textX = cxPointer + dp(12f)
        val textY = cyPointer + (currentTextPaint.textSize * 0.35f)
        canvas.drawText(label, textX, textY, currentTextPaint)

        val stepsVisible = ceil((h / 2f) / tickSpacingPx).toInt() + 2
        val tickRight = w - dp(16f)
        val smallTickLeft = tickRight - dp(14f)
        val bigTickLeft = tickRight - dp(28f)

        val currIndex = ((focusValue - minZoom) / step)
        val baseIndex = floor(currIndex).toInt()

        for (i in (baseIndex - stepsVisible)..(baseIndex + stepsVisible)) {
            val v = minZoom + i * step
            if (v < minZoom - 1e-4 || v > maxZoom + 1e-4) continue

            val y = cyPointer + ((focusValue - v) / step) * tickSpacingPx

            val tolerance = 0.001f
            val isMajor = zoomLevels.any { abs(it - v) < tolerance }

            val left = if (isMajor) bigTickLeft else smallTickLeft
            val paint = if (isMajor) tickPaint else faintTickPaint

            canvas.drawLine(left, y, tickRight, y, paint)

            if (isMajor) {
                val isSelected = abs(v - focusValue) <= selectionThreshold
                if (!isSelected) {
                    val t = String.format("%.2fx", v)
                    val tw = majorTextPaint.measureText(t)
                    val ty = y + (majorTextPaint.textSize * 0.35f)
                    canvas.drawText(t, left - dp(8f) - tw, ty, majorTextPaint)
                }
            }
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity
}
