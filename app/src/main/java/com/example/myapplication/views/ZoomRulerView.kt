package com.opic3d.Spatial.trendingvideos.views

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
import com.opic3d.Spatial.trendingvideos.R
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round

class ZoomRulerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {


    // Public API
    var onZoomChanged: ((Float) -> Unit)? = null
    private var increaseUpwards = true // values grow toward the top of the view

    var minZoom = 1.0f
        get() = field
        set(v) {
            field = v; zoomValue = zoomValue.coerceIn(minZoom, maxZoom); invalidate()
        }

    var maxZoom = 5.0f
        set(v) {
            field = v; zoomValue = zoomValue.coerceIn(minZoom, maxZoom); invalidate()
        }

    var step = 0.1f
        set(v) {
            field = v; invalidate()
        }

    // ZoomRulerView.kt, near class-level vars
    private val selectionThreshold =
        step / 2  // Values within ±0.05 of current value are considered selected

    /** Current zoom ratio (1.0..3.0). Setting it animates no fling; we snap draw immediately. */
    var zoomValue = 1.0f
        set(v) {
            val nv = v.coerceIn(minZoom, maxZoom)
            if (field != nv) {
                field = nv
                onZoomChanged?.invoke(field)
                invalidate()
            }
        }

    // Styling defaults
    private var tickSpacingPx = dp(5f)   // space between each 0.1 step
    private var textSizePx = sp(11f)
    private var mediumTextSizePx = sp(12f)
    private var pointerRadiusPx = dp(6f)

    // Paints
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        strokeWidth = dp(2f)
        style = Paint.Style.STROKE
    }
    private val faintTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66444444")
        strokeWidth = dp(2f)
        style = Paint.Style.STROKE
    }
    private val majorTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        textSize = textSizePx
    }
    private val minorTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#999999")
        textSize = textSizePx * 0.9f
    }
    private val pointerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF2D55") // pink dot
        style = Paint.Style.FILL
    }
    private val currentTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = textSizePx * 1.1f
        style = Paint.Style.FILL
    }
    private val accentBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(4f)
        strokeCap = Paint.Cap.ROUND
    }

    // Gesture/scroll
    private val scroller = OverScroller(context)

    init {
        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.ZoomRulerView)
            if (a.hasValue(R.styleable.ZoomRulerView_zr_minZoom)) minZoom =
                a.getFloatOrThrow(R.styleable.ZoomRulerView_zr_minZoom)
            if (a.hasValue(R.styleable.ZoomRulerView_zr_maxZoom)) maxZoom =
                a.getFloatOrThrow(R.styleable.ZoomRulerView_zr_maxZoom)
            if (a.hasValue(R.styleable.ZoomRulerView_zr_step)) step =
                a.getFloatOrThrow(R.styleable.ZoomRulerView_zr_step)
            if (a.hasValue(R.styleable.ZoomRulerView_zr_tickSpacing)) tickSpacingPx =
                a.getDimensionOrThrow(R.styleable.ZoomRulerView_zr_tickSpacing)
            if (a.hasValue(R.styleable.ZoomRulerView_zr_textSize)) textSizePx =
                a.getDimensionOrThrow(R.styleable.ZoomRulerView_zr_textSize)
            if (a.hasValue(R.styleable.ZoomRulerView_zr_pointerRadius)) pointerRadiusPx =
                a.getDimensionOrThrow(R.styleable.ZoomRulerView_zr_pointerRadius)
            a.recycle()
            majorTextPaint.textSize = textSizePx
            minorTextPaint.textSize = textSizePx * 0.9f
            currentTextPaint.textSize = mediumTextSizePx * 1.1f
        }
        // Gradient text & accent bar (cyan→magenta)
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

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val minW = (dp(80f)).toInt() // enough room for ticks + labels
        val w = resolveSize(minW, widthMeasureSpec)
        val h = resolveSize(suggestedMinimumHeight, heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    private var lastY = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastY = event.y
                scroller.forceFinished(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dy = event.y - lastY
                lastY = event.y
                // Dragging downward increases value, upward decreases.
                val deltaZoom = dy / tickSpacingPx * step
                zoomValue = (zoomValue + deltaZoom).coerceIn(minZoom, maxZoom)
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Optionally snap to nearest step
                val snapped = round(zoomValue / step) * step
                zoomValue = (snapped * 10f).toInt() / 10f.toFloat()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            // Map scroller position back to a zoom value
            zoomValue = (scroller.currY.toFloat() / tickSpacingPx * step)
                .coerceIn(minZoom, maxZoom)
            postInvalidateOnAnimation()
        }
    }


    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val cxPointer = dp(12f)   // pointer x (fixed)
        val cyPointer = h / 2f    // pointer y (fixed center)

        // Draw fixed pointer (red dot)
        canvas.drawCircle(cxPointer, cyPointer, pointerRadiusPx, pointerPaint)

        // Current value label (e.g., "2.3x") to the right of the pointer
        val label = String.format("%.1fx", zoomValue)
        val textX = cxPointer + dp(12f)
        val textY = cyPointer + (currentTextPaint.textSize * 0.35f)
        canvas.drawText(label, textX, textY, currentTextPaint)


        // Accent bar near label (to mimic screenshot)
        val barStartX = textX + currentTextPaint.measureText(label) + dp(6f)
        val barEndX = barStartX + dp(26f)
//        canvas.drawLine(barStartX, cyPointer, barEndX, cyPointer, accentBarPaint)

        // Draw ticks: scale moves relative to current value so compute visible value range
        val stepsVisibleAbove = ceil((h / 2f) / tickSpacingPx).toInt() + 2
        val stepsVisibleBelow = stepsVisibleAbove

        // Left margin for labels; ticks drawn near right edge
        val tickRight = w - dp(16f)
        val smallTickLeft = tickRight - dp(14f)
        val midTickLeft = tickRight - dp(20f)
        val bigTickLeft = tickRight - dp(28f)

        // For every visible step around current value:
        val currIndex = ((zoomValue - minZoom) / step)
        val baseIndex = floor(currIndex).toInt()

        // Draw central baseline ticks
        for (i in (baseIndex - stepsVisibleBelow)..(baseIndex + stepsVisibleAbove)) {
            val v = minZoom + i * step
            if (v < minZoom - 1e-3 || v > maxZoom + 1e-3) continue

            val dySteps = (v - zoomValue) / step
// after (values increase upward):
            val y = cyPointer + ((zoomValue - v) / step) * tickSpacingPx
            // Decide tick length
            val nearInt = abs((v * 10f).toInt() % 10) == 0        // every 1.0
            val nearHalf = abs((v * 10f).toInt() % 10) == 5       // every 0.5

            val isSpecial = abs(v - 1.2f) < 0.05f   // 🔥 treat 1.2x as a major
            val isMajor = nearInt || isSpecial
            val isMedium = !isMajor && nearHalf

            val left = when {
                isMajor -> bigTickLeft
                isMedium -> midTickLeft
                else -> smallTickLeft
            }

            val p = if (isMajor) tickPaint else faintTickPaint
            canvas.drawLine(left, y, tickRight, y, p)

            // Major labels: 1.0x, 2.0x, 3.0x on the left side

            if (isMajor) {
                val isSelected = abs(v - zoomValue) <= selectionThreshold

                if (!isSelected) {
                    // Draw the gray major tick label
                    val t = String.format("%.1fx", v)
                    val tw = majorTextPaint.measureText(t)
                    val ty = y + (majorTextPaint.textSize * 0.35f)
                    canvas.drawText(t, left - dp(8f) - tw, ty, majorTextPaint)
                }
                // Selected one is omitted entirely (no drawing)
            } else {
                // Only draw minor "1.2x" if it's NOT selected
                if (abs(v - 1.2f) < 0.05f && abs(v - zoomValue) > selectionThreshold) {
                    val t = "1.2x"
                    val tw = minorTextPaint.measureText(t)
                    val ty = y + (minorTextPaint.textSize * 0.35f)
                    canvas.drawText(t, left - dp(8f) - tw, ty, minorTextPaint)
                }
            }
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity
}
