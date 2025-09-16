package com.example.myapplication.controllers


/**
 * Created by Faheem Abbas on 15/09/2025.
 * Technical Lead
 * Bajco Technologies
 * faheem.abbas@bajcotechnologies.com
 * +923115284424
 */
// ModeSelectorController.kt (use inside your Activity/Fragment/CustomView)

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.example.myapplication.adapters.CameraModeAdapter

class ModeSelectorController(
    private val rv: RecyclerView,
    private val onSelectionChanged: (isVideo: Boolean) -> Unit
) {
    private val lm = LinearLayoutManager(rv.context, RecyclerView.HORIZONTAL, false)
    private val snap = LinearSnapHelper()

    private val adapter: CameraModeAdapter

    init {
        rv.layoutManager = lm
        val ctx: Context = rv.context
        adapter = CameraModeAdapter(
            modes = listOf("VIDEO", "PHOTO"),
            selectedColor = ContextCompat.getColor(ctx, R.color.mode_selected),
            unselectedColor = ContextCompat.getColor(ctx, R.color.mode_unselected)
        )
        rv.adapter = adapter
        snap.attachToRecyclerView(rv)

        // Center initial item (e.g., VIDEO at index 0)
        rv.post { smoothCenter(adapter.selectedPosition, notify = true) }

        // Click to select
        adapter.onModeSelected = { pos, _ -> smoothCenter(pos, notify = true) }

        // Listen to scrolls; when settling, figure out the centered item
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    val centerView = snap.findSnapView(lm) ?: return
                    val pos = lm.getPosition(centerView)
                    if (pos != adapter.selectedPosition) {
                        adapter.selectedPosition = pos
                        onSelectionChanged(pos == 0) // 0=VIDEO, 1=PHOTO
                    }
                }
            }
        })
    }

    fun setIsVideo(isVideo: Boolean) {
        val pos = if (isVideo) 0 else 1
        smoothCenter(pos, notify = false)
    }

    private fun smoothCenter(position: Int, notify: Boolean) {
        rv.smoothScrollToPosition(position)
        adapter.selectedPosition = position
        if (notify) onSelectionChanged(position == 0)
    }
}
