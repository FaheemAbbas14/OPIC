package com.example.myapplication.controllers

import android.content.Context
import android.graphics.Canvas
import android.graphics.PointF
import android.graphics.Rect
import android.view.View
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.example.myapplication.adapters.CameraModeAdapter

class ModeSelectorController(
    private val rv: RecyclerView,
    private val onSelectionChanged: (isVideo: Boolean) -> Unit
) {
    // Plain HORIZONTAL manager (no fling override here)
    private val lm = LinearLayoutManager(rv.context, RecyclerView.HORIZONTAL, false)

    // Snap exactly one item per fling / scroll
    private val snap = OneStepPagerSnapHelper()

    private val adapter: CameraModeAdapter

    private val itemSpacingPx: Int =
        runCatching { rv.resources.getDimensionPixelSize(R.dimen.mode_item_spacing) }.getOrDefault(0)
    private val fixedItemWidthPx: Int =
        runCatching { rv.resources.getDimensionPixelSize(R.dimen.camera_mode_item_width) }.getOrDefault(0)

    init {
        rv.layoutManager = lm
        rv.setHasFixedSize(true)
        rv.isNestedScrollingEnabled = false

        // Kill stretch/glow bounce
        rv.overScrollMode = View.OVER_SCROLL_NEVER
        rv.edgeEffectFactory = object : RecyclerView.EdgeEffectFactory() {
            override fun createEdgeEffect(recyclerView: RecyclerView, direction: Int) =
                object : android.widget.EdgeEffect(recyclerView.context) {
                    override fun onPull(deltaDistance: Float) {}
                    override fun onPull(deltaDistance: Float, displacement: Float) {}
                    override fun onRelease() {}
                    override fun onAbsorb(velocity: Int) {}
                    override fun draw(canvas: Canvas?): Boolean = false
                    override fun isFinished(): Boolean = true
                }
        }
        rv.itemAnimator = null
        rv.clipToPadding = false
        if (itemSpacingPx > 0) rv.addItemDecoration(Spaces(itemSpacingPx))

        val ctx: Context = rv.context
        adapter = CameraModeAdapter(
            modes = listOf("OPIC VIDEO", "OPIC PHOTO"),
            selectedColor = ContextCompat.getColor(ctx, R.color.mode_selected),
            unselectedColor = ContextCompat.getColor(ctx, R.color.mode_unselected)
        )
        rv.adapter = adapter

        // Make sure nothing else is listening for flings, then attach
        rv.onFlingListener = null
        snap.attachToRecyclerView(rv)

        // Tap → smooth center
        adapter.onModeSelected = { pos, _ -> smoothCenter(pos) }

        // Set selection only when idle (after snap)
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    val center = snap.findSnapView(lm) ?: return
                    val pos = lm.getPosition(center)
                    if (pos != adapter.selectedPosition) {
                        adapter.selectedPosition = pos
                        onSelectionChanged(pos == 0) // 0 = VIDEO, 1 = PHOTO
                    }
                }
            }
        })

        // Side padding so first/last can be centered; then center initial
        applySidePaddingThenCenterInitial()
    }

    fun setIsVideo(isVideo: Boolean) = smoothCenter(if (isVideo) 0 else 1)

    /** Smoothly center a position (align item center to RV center). */
    private fun smoothCenter(position: Int) {
        val scroller = object : LinearSmoothScroller(rv.context) {
            override fun computeScrollVectorForPosition(targetPosition: Int): PointF? =
                lm.computeScrollVectorForPosition(targetPosition)

            override fun calculateDtToFit(
                viewStart: Int, viewEnd: Int,
                boxStart: Int, boxEnd: Int,
                snapPreference: Int
            ): Int {
                val viewCenter = viewStart + (viewEnd - viewStart) / 2
                val boxCenter = boxStart + (boxEnd - boxStart) / 2
                return boxCenter - viewCenter
            }
        }
        scroller.targetPosition = position
        lm.startSmoothScroll(scroller)
        // selection finalized on IDLE to avoid jitter
    }

    private fun applySidePaddingThenCenterInitial() {
        fun apply(itemW: Int) {
            val rvW = rv.width.takeIf { it > 0 } ?: rv.resources.displayMetrics.widthPixels
            val pad = ((rvW - itemW) / 2f).toInt().coerceAtLeast(0)
            rv.setPadding(pad, rv.paddingTop, pad, rv.paddingBottom)
            rv.post { smoothCenter(adapter.selectedPosition) }
        }

        if (fixedItemWidthPx > 0) {
            rv.post { apply(fixedItemWidthPx) }
        } else {
            rv.post {
                val child = rv.getChildAt(0)
                if (child != null && child.width > 0) apply(child.width)
                else {
                    rv.scrollToPosition(0)
                    rv.post { apply(rv.getChildAt(0)?.width ?: (rv.width / 3)) }
                }
            }
        }
    }

    /** Clamp snap to at most +/-1 item (camera-like). */
    private class OneStepPagerSnapHelper : PagerSnapHelper() {
        override fun findTargetSnapPosition(
            layoutManager: RecyclerView.LayoutManager,
            velocityX: Int,
            velocityY: Int
        ): Int {
            val base = super.findTargetSnapPosition(layoutManager, velocityX, velocityY)
            if (base == RecyclerView.NO_POSITION) return base
            val current = findSnapView(layoutManager) ?: return base
            val curPos = layoutManager.getPosition(current)
            return when {
                base > curPos -> curPos + 1
                base < curPos -> curPos - 1
                else -> curPos
            }
        }
    }

    private class Spaces(private val space: Int) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(out: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
            if (parent.getChildAdapterPosition(view) != RecyclerView.NO_POSITION) out.right = space
        }
    }
}
