package com.opic3d.Spatial.trendingvideos.controllers

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
import com.opic3d.Spatial.trendingvideos.R
import com.opic3d.Spatial.trendingvideos.adapters.CameraModeAdapter

class ModeSelectorController(
    private val rv: RecyclerView,
    private val onSelectionChanged: (index: Int) -> Unit
) {
    // Horizontal list with one-step pager snap
    private val lm = LinearLayoutManager(rv.context, RecyclerView.HORIZONTAL, false)
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

        // Disable overscroll effects
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
        var modes=mutableListOf<String>("OPIC VIDEO", "OPIC PHOTO", "OPIC TIME-LAPSE", "OPIC SLOWMO")
       // if (check3d){
            modes.add("OPIC 3D PHOTO")
            modes.add("OPIC 3D VIDEO")
      //  }
        adapter = CameraModeAdapter(
            modes = modes,
            selectedColor = ContextCompat.getColor(ctx, R.color.mode_selected),
            unselectedColor = ContextCompat.getColor(ctx, R.color.mode_unselected)
        )
        rv.adapter = adapter

        // Enforce one-snap behavior
        rv.onFlingListener = null
        snap.attachToRecyclerView(rv)

        // Tap → smooth center that item
        adapter.onModeSelected = { pos, _ -> smoothCenter(pos) }

        // When scrolling stops, update selection + callback
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    val center = snap.findSnapView(lm) ?: return
                    val pos = lm.getPosition(center).coerceIn(0, adapter.itemCount - 1)
                    if (pos != adapter.selectedPosition) {
                        adapter.selectedPosition = pos
                        onSelectionChanged(pos) // 0=VIDEO, 1=PHOTO, 2=SLOWMO
                    } else {
                        // Even if same, ensure callback once at init
                        // (optional, keep if you need a guaranteed initial fire)
                    }
                }
            }
        })

        // Side padding so items can center; then center initial selection (0)
        applySidePaddingThenCenterInitial()
    }

    /**
     * Kept for backward-compat with your old code.
     * With 3 modes now, true -> VIDEO (index 0), false -> PHOTO (index 1).
     */
    fun setIsVideo(isVideo: Boolean) = smoothCenter(if (isVideo) 0 else 1)

    /** Programmatically set the selected index (0=VIDEO, 1=PHOTO, 2=SLOWMO). */
    fun setIndex(index: Int, notifyNow: Boolean = false) {
        val clamped = index.coerceIn(0, (adapter.itemCount - 1).coerceAtLeast(0))
        if (clamped == adapter.selectedPosition) {
            // Still re-center visually
            smoothCenter(clamped)
            if (notifyNow) onSelectionChanged(clamped)
            return
        }
        adapter.selectedPosition = clamped
        smoothCenter(clamped)
        if (notifyNow) onSelectionChanged(clamped)
        // Otherwise, onSelectionChanged will be fired on IDLE after snap
    }

    fun setVideoNormal(notifyNow: Boolean = false) = setIndex(0, notifyNow)
    fun setPhoto(notifyNow: Boolean = false) = setIndex(1, notifyNow)
    fun setSlowMo(notifyNow: Boolean = false) = setIndex(2, notifyNow)

    fun currentIndex(): Int = adapter.selectedPosition

    /** Smoothly center a position (align item center to RV center). */
    private fun smoothCenter(position: Int) {
        val scroller = object : LinearSmoothScroller(rv.context) {
            override fun computeScrollVectorForPosition(targetPosition: Int): PointF? =
                lm.computeScrollVectorForPosition(targetPosition)

            override fun calculateDtToFit(
                viewStart: Int,
                viewEnd: Int,
                boxStart: Int,
                boxEnd: Int,
                snapPreference: Int
            ): Int {
                val viewCenter = viewStart + (viewEnd - viewStart) / 2
                val boxCenter = boxStart + (boxEnd - boxStart) / 2
                return boxCenter - viewCenter
            }
        }
        scroller.targetPosition = position
        lm.startSmoothScroll(scroller)
        // Selection callback emitted when scroll state becomes IDLE
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
                if (child != null && child.width > 0) {
                    apply(child.width)
                } else {
                    rv.scrollToPosition(adapter.selectedPosition)
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
            val currentView = findSnapView(layoutManager) ?: return base
            val curPos = layoutManager.getPosition(currentView)
            return when {
                base > curPos -> curPos + 1
                base < curPos -> curPos - 1
                else -> curPos
            }
        }
    }

    private class Spaces(private val space: Int) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(out: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
            if (parent.getChildAdapterPosition(view) != RecyclerView.NO_POSITION) {
                out.right = space
            }
        }
    }
}
