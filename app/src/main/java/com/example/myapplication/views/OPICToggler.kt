package com.example.myapplication.views

import com.example.myapplication.R
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.withSave

class OPICToggler @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // Labels
    private var leftLabel: String = "VIDEO"
    private var rightLabel: String = "PHOTO"

    // State
    private var isVideoSelected: Boolean = true
    private var slideProgress: Float = 0f // 0 = left, 1 = right

    // Colors
    private var trackColor: Int = Color.parseColor("#1AFFFFFF")
    private var selectedColorStart: Int = Color.parseColor("#1CF3FF")
    private var selectedColorEnd: Int = Color.parseColor("#FD2F55")
    private var textColor: Int = Color.WHITE
    private var selectedTextColor: Int = Color.BLACK

    // Sizing
    private var cornerRadius: Float = dp(999f) // pill
    private var textSizePx: Float = sp(14f)
    private var strokeWidthPx: Float = 0f

    // NEW: spacing & inset
    private var segmentGapPx: Float = dp(6f)    // gap between VIDEO/PHOTO
    private var segmentInsetPx: Float = dp(2f)  // inner padding for selected pill

    // Animation
    private var enableSlideAnimation: Boolean = true
    private var animationDurationMs: Long = 180L

    // Paints
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = trackColor
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.TRANSPARENT
        strokeWidth = strokeWidthPx
    }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
        textSize = textSizePx
        color = textColor
    }
    private val selectedTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
        textSize = textSizePx
        color = selectedTextColor
    }

    // Layout helpers
    private val outerRect = RectF()
    private val leftSeg = RectF()
    private val rightSeg = RectF()
    private val selectedRect = RectF()
    private var gradient: LinearGradient? = null

    // Listener
    interface OnModeChangeListener { fun onModeChanged(isVideo: Boolean) }
    private var listener: OnModeChangeListener? = null

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)

        // Read attributes
        attrs?.let {
            val ta = context.obtainStyledAttributes(it, R.styleable.OpicTextView)
            leftLabel = ta.getString(R.styleable.OpicTextView_leftLabel) ?: leftLabel
            rightLabel = ta.getString(R.styleable.OpicTextView_rightLabel) ?: rightLabel
            isVideoSelected = ta.getBoolean(R.styleable.OpicTextView_initialIsVideo, true)

            trackColor = ta.getColor(R.styleable.OpicTextView_trackColor, trackColor)
            selectedColorStart = ta.getColor(R.styleable.OpicTextView_selectedColorStart, selectedColorStart)
            selectedColorEnd = ta.getColor(R.styleable.OpicTextView_selectedColorEnd, selectedColorEnd)
            textColor = ta.getColor(R.styleable.OpicTextView_textColor, textColor)
            selectedTextColor = ta.getColor(R.styleable.OpicTextView_selectedTextColor, selectedTextColor)

            val radiusDim = ta.getDimension(R.styleable.OpicTextView_cornerRadius, cornerRadius)
            cornerRadius = if (radiusDim > 0f) radiusDim else cornerRadius

            val ts = ta.getDimension(R.styleable.OpicTextView_textSizeSp, 0f)
            if (ts > 0f) {
                textSizePx = ts
                textPaint.textSize = ts
                selectedTextPaint.textSize = ts
            }

            strokeWidthPx = ta.getDimension(R.styleable.OpicTextView_opstrokeWidth, strokeWidthPx)
            strokePaint.strokeWidth = strokeWidthPx

            enableSlideAnimation = ta.getBoolean(R.styleable.OpicTextView_enableSlideAnimation, true)
            animationDurationMs = ta.getInt(R.styleable.OpicTextView_animationDurationMs, animationDurationMs.toInt()).toLong()

            // NEW
            segmentGapPx = ta.getDimension(R.styleable.OpicTextView_segmentGap, segmentGapPx)
            segmentInsetPx = ta.getDimension(R.styleable.OpicTextView_segmentInset, segmentInsetPx)

            ta.recycle()
        }

        slideProgress = if (isVideoSelected) 0f else 1f
        contentDescription = if (isVideoSelected) "$leftLabel selected" else "$rightLabel selected"
        isClickable = true
        isFocusable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val fm = textPaint.fontMetrics
        val textHeight = fm.bottom - fm.top
        val desiredH = paddingTop + paddingBottom + textHeight + dp(16f)
        val desiredW = paddingLeft + paddingRight + dp(180f) // a bit wider for the gap
        val w = resolveSize(desiredW.toInt(), widthMeasureSpec)
        val h = resolveSize(desiredH.toInt(), heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        outerRect.set(paddingLeft.toFloat(), paddingTop.toFloat(), (w - paddingRight).toFloat(), (h - paddingBottom).toFloat())

        // Compute two segment rects with a gap in the middle
        val totalWidth = outerRect.width()
        val eachWidth = (totalWidth - segmentGapPx) / 2f

        leftSeg.set(outerRect.left, outerRect.top, outerRect.left + eachWidth, outerRect.bottom)
        rightSeg.set(leftSeg.right + segmentGapPx, outerRect.top, outerRect.right, outerRect.bottom)

        // Gradient for the selected segment
        gradient = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            intArrayOf(selectedColorStart, selectedColorEnd),
            null,
            Shader.TileMode.CLAMP
        )
        selectedPaint.shader = gradient
        trackPaint.color = trackColor
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw left and right track "buttons" with round corners
        canvas.drawPath(roundRectPath(leftSeg, leftCorners = true, rightCorners = true), trackPaint)
        canvas.drawPath(roundRectPath(rightSeg, leftCorners = true, rightCorners = true), trackPaint)

        // Selected rect interpolates between left and right segments (respecting inset)
        val selLeft = lerp(leftSeg.left + segmentInsetPx, rightSeg.left + segmentInsetPx, slideProgress)
        val selRight = lerp(leftSeg.right - segmentInsetPx, rightSeg.right - segmentInsetPx, slideProgress)
        val selTop = outerRect.top + segmentInsetPx
        val selBottom = outerRect.bottom - segmentInsetPx
        selectedRect.set(selLeft, selTop, selRight, selBottom)

        // Draw selected gradient pill
        canvas.withSave {
            val path = Path()
            path.addRoundRect(selectedRect, cornerRadius, cornerRadius, Path.Direction.CW)
            canvas.clipPath(path)
            canvas.drawRect(selectedRect, selectedPaint)
        }

        // Optional border around each track button
        if (strokeWidthPx > 0f && strokePaint.color != Color.TRANSPARENT) {
            canvas.drawPath(roundRectPath(leftSeg, true, true), strokePaint)
            canvas.drawPath(roundRectPath(rightSeg, true, true), strokePaint)
        }

        // Text centered in each segment; selected side uses selectedTextColor
        val cy = outerRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
        val leftPaint = if (slideProgress < 0.5f) selectedTextPaint else textPaint
        val rightPaint = if (slideProgress >= 0.5f) selectedTextPaint else textPaint

        canvas.drawText(leftLabel, leftSeg.centerX(), cy, leftPaint)
        canvas.drawText(rightLabel, rightSeg.centerX(), cy, rightPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { isPressed = true; return true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasPressed = isPressed
                isPressed = false
                if (event.actionMasked == MotionEvent.ACTION_UP && wasPressed) {
                    val tappedRight = event.x > width / 2f
                    setIsVideoSelected(!tappedRight, fromUser = true)
                    performClick()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun setOnModeChangeListener(l: OnModeChangeListener?) { listener = l }

    fun setIsVideoSelected(value: Boolean, fromUser: Boolean = false) {
        if (value == isVideoSelected && ((value && slideProgress == 0f) || (!value && slideProgress == 1f))) return
        val target = if (value) 0f else 1f
        if (enableSlideAnimation) {
            ValueAnimator.ofFloat(slideProgress, target).apply {
                duration = animationDurationMs
                addUpdateListener {
                    slideProgress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            slideProgress = target
            invalidate()
        }
        isVideoSelected = value
        contentDescription = if (isVideoSelected) "$leftLabel selected" else "$rightLabel selected"
        listener?.onModeChanged(isVideoSelected)
    }

    fun isVideoSelected(): Boolean = isVideoSelected

    fun setLabels(left: String, right: String) {
        leftLabel = left
        rightLabel = right
        invalidate()
    }

    // Helpers
    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    // Build a rounded-rect path where all corners are rounded (pill look).
    // Kept params to allow future per-corner control if needed.
    private fun roundRectPath(r: RectF, leftCorners: Boolean, rightCorners: Boolean): Path {
        val radii = floatArrayOf(
            cornerRadius, cornerRadius, // top-left
            cornerRadius, cornerRadius, // top-right
            cornerRadius, cornerRadius, // bottom-right
            cornerRadius, cornerRadius  // bottom-left
        )
        return Path().apply { addRoundRect(r, radii, Path.Direction.CW) }
    }
}
