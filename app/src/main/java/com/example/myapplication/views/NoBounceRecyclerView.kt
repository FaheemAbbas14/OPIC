package com.example.myapplication.views


/**
 * Created by Faheem Abbas on 18/09/2025.
 * Technical Lead
 * Bajco Technologies
 * faheem.abbas@bajcotechnologies.com
 * +923115284424
 */

import android.content.Context
import android.util.AttributeSet
import androidx.recyclerview.widget.RecyclerView

class NoBounceRecyclerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    override fun overScrollBy(
        deltaX: Int, deltaY: Int,
        scrollX: Int, scrollY: Int,
        scrollRangeX: Int, scrollRangeY: Int,
        maxOverScrollX: Int, maxOverScrollY: Int,
        isTouchEvent: Boolean
    ): Boolean {
        // Block overscroll distances
        return super.overScrollBy(
            deltaX, deltaY, scrollX, scrollY,
            scrollRangeX, scrollRangeY,
            0, 0, isTouchEvent
        )
    }

    init { overScrollMode = OVER_SCROLL_NEVER }
}
