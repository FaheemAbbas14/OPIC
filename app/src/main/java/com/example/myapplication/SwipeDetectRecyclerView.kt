package com.example.myapplication

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.View.VISIBLE
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.views.ZoomRulerView
import kotlin.math.abs

class SwipeDetectRecyclerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : RecyclerView(context, attrs) {

    private val vc = ViewConfiguration.get(context)
    private val touchSlop = vc.scaledTouchSlop
    private val minFlingVelocity = vc.scaledMinimumFlingVelocity
    var rulerView: ZoomRulerView? = null // you’ll set this from your Activity/Fragment

    private val gestureDetector =
        GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (e1 == null) return false
                val deltaY = e2.y - e1.y

                if (abs(deltaY) > touchSlop) {
                    if (deltaY > 0) onSwipeDown?.invoke() else onSwipeUp?.invoke()
                    return true
                }
                return false
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (abs(velocityY) >= minFlingVelocity) {
                    if (e1 != null && e2.y > e1.y) onSwipeDown?.invoke()
                    else if (e1 != null && e2.y < e1.y) onSwipeUp?.invoke()
                    return true
                }
                return false
            }
        })

    var onSwipeUp: (() -> Unit)? = null
    var onSwipeDown: (() -> Unit)? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(ev)

        // If ruler is visible, forward touches to it
        if (isRulerVisible()) {
            rulerView?.dispatchTouchEvent(ev)
            return true // consume so RecyclerView doesn't also scroll
        }

        return super.dispatchTouchEvent(ev)
    }

    private fun isRulerVisible(): Boolean {
        return rulerView?.visibility == VISIBLE
    }
}