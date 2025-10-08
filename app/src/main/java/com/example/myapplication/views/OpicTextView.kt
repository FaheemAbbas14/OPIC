package com.opic3d.Spatial.trendingvideos.views

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.opic3d.Spatial.trendingvideos.R   // NEW: import your R

public class OpicTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0                 // NEW
) : View(context, attrs, defStyleAttr) {  // CHANGED

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0f
        maskFilter = BlurMaskFilter(2f, BlurMaskFilter.Blur.NORMAL)
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

    // CHANGED: keep property name but it now respects XML attributes
    var timerText: String = "OPIC SPATIAL"
        set(value) {
            field = value
            requestLayout()  // NEW: size can change with new text
            invalidate()
        }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)

        // NEW: read custom attributes
        if (attrs != null) {
            val ta = context.obtainStyledAttributes(attrs, R.styleable.OpicTextView, defStyleAttr, 0)
            timerText = ta.getString(R.styleable.OpicTextView_opicText) ?: timerText
            val xmlSize = ta.getDimension(R.styleable.OpicTextView_opicTextSize, textPaint.textSize)
            textPaint.textSize = xmlSize
            ta.recycle()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val radius = height / 2f
        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())

        // Transparent background pill
        canvas.drawRoundRect(rect, radius, radius, backgroundPaint)

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

    // NEW: make wrap_content size to text + padding
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val textWidth = textPaint.measureText(timerText)
        val fm = textPaint.fontMetrics
        val textHeight = (fm.bottom - fm.top)

        // Use view paddings; add a little extra horizontal breathing room
        val extraH = dp(16f)
        val desiredW = (paddingLeft + textWidth + extraH + paddingRight).toInt()
        val desiredH = (paddingTop + textHeight + paddingBottom).toInt()

        val w = resolveSize(desiredW, widthMeasureSpec)
        val h = resolveSize(desiredH, heightMeasureSpec)

        setMeasuredDimension(w, h)
    }

    // NEW: dp helper
    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
