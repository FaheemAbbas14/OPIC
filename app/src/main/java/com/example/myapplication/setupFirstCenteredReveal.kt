package com.example.myapplication


/**
 * Created by Faheem Abbas on 17/09/2025.
 * Technical Lead
 * Bajco Technologies
 * faheem.abbas@bajcotechnologies.com
 * +923115284424
 */
import android.animation.ValueAnimator
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import androidx.annotation.LayoutRes
import androidx.core.view.doOnPreDraw
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.max
import kotlin.math.roundToInt

fun setupFirstCenteredReveal(
    rv: RecyclerView,
    @LayoutRes itemLayoutRes: Int,
    revealDelayMs: Long = 250,          // how long to keep only the first item centered
    revealAnimMs: Long = 250,           // padding animation duration
    normalSidePaddingDp: Int = 0        // padding after reveal (0 = edge-to-edge)
) {
    // Horizontal LM (scroll axis), even if the view is rotated
    val lm = LinearLayoutManager(rv.context, RecyclerView.HORIZONTAL, false)
    rv.layoutManager = lm

    // Snap items to center
    val snap = LinearSnapHelper()
    snap.attachToRecyclerView(rv)

    // Block scroll initially
    val blockTouch = View.OnTouchListener { _, _ -> true }
    rv.setOnTouchListener(blockTouch)

    rv.doOnPreDraw {
        // Make sure we know the item width
        val firstItemWidth = rv.findViewHolderForAdapterPosition(0)?.itemView?.measuredWidth
            ?: run {
                val v = LayoutInflater.from(rv.context).inflate(itemLayoutRes, rv, false)
                v.measure(
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                v.measuredWidth
            }

        // For rotated RV (90/270), the visual width is the view's HEIGHT
        val containerWidth = if (rv.rotation % 180f != 0f) rv.height else rv.width
        val startSidePadding = max(0, (containerWidth - firstItemWidth) / 2)

        // Apply big side padding so item 0 sits centered
        rv.setPadding(startSidePadding, rv.paddingTop, startSidePadding, rv.paddingBottom)
        rv.scrollToPosition(0) // put first item at start, SnapHelper centers it

        // Reveal later: animate padding to "normal" and enable scroll
        rv.postDelayed({
            val endSidePadding = dp(rv, normalSidePaddingDp)

            val anim = ValueAnimator.ofInt(startSidePadding, endSidePadding).setDuration(revealAnimMs)
            anim.addUpdateListener { va ->
                val p = va.animatedValue as Int
                rv.setPadding(p, rv.paddingTop, p, rv.paddingBottom)
            }
            anim.start()

            // Re-enable touch/scroll
            rv.setOnTouchListener(null)
        }, revealDelayMs)
    }
}
fun Int.dp(context: Context): Int =
    (this * context.resources.displayMetrics.density).roundToInt()
private fun dp(rv: RecyclerView, value: Int): Int =
    (value * rv.resources.displayMetrics.density).toInt()
