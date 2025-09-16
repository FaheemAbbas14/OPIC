package com.example.myapplication.controllers

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.example.myapplication.views.ZoomLevelView
import java.util.Locale
import kotlin.math.roundToInt

class ZoomAdapterControl(
    private val originalZoomLevels: MutableList<Float>,
    private val listener: OnZoomClick
) : RecyclerView.Adapter<ZoomAdapterControl.VH?>() {

    private var zoomLevels: MutableList<Float> = originalZoomLevels.toMutableList()
    private var selIdx: Int = RecyclerView.NO_POSITION

    /**
     * Change exactly one zoom step dynamically.
     * Resets all other values back to original.
     */
    fun updateSingleZoomStep(target: Int, newValue: Float) {
        // reset to original
        zoomLevels = originalZoomLevels.toMutableList()


        // find the index of the target value
        var index = zoomLevels.indexOfFirst { it?.roundToInt() == target }
        if (newValue == 1.2f) {
            index = 4
        }
        if (newValue == 1f) {
            index = 5

        }
        if (index != -1) {
            zoomLevels[index] = newValue
            selIdx = index
            notifyDataSetChanged()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_zoom_control_level, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, @SuppressLint("RecyclerView") position: Int) {
        val r = zoomLevels[position]
        holder.view.setText(String.format(Locale.US, "%.1fx", r))
        val sel = (position == selIdx)
        holder.view.setSelectedState(sel)
        holder.view.setAlpha(if (sel) 1f else 0.6f)
        holder.view.setTextSize(11f)

        if (sel) {
            val leftColor = ColorUtils.setAlphaComponent(Color.parseColor("#1CF3FF"), 200)
            val rightColor = ColorUtils.setAlphaComponent(Color.parseColor("#FD2F55"), 200)

            val shader: Shader = LinearGradient(
                0f, 0f,
                holder.view.getWidth().toFloat(), 0f,
                intArrayOf(leftColor, rightColor),
                null,
                Shader.TileMode.REPEAT
            )
            holder.view.getPaint().setShader(shader)
        } else {
            holder.view.getPaint().setShader(null)
            holder.view.setTextColor(Color.WHITE) // fallback color
        }

        holder.view.setOnClickListener(View.OnClickListener { v: View? ->
            selIdx = position
            notifyDataSetChanged()
            listener.onZoomClick(r)
        })
    }

    override fun getItemCount(): Int {
        return zoomLevels.size
    }

    fun selectRatio(ratio: Float) {
        val idx = zoomLevels.indexOf(ratio)
        if (idx >= 0) {
            selIdx = idx
            notifyDataSetChanged()
        }
    }

    interface OnZoomClick {
        fun onZoomClick(r: Float)
    }

    class VH internal constructor(v: View) : RecyclerView.ViewHolder(v) {
        var view: ZoomLevelView

        init {
            view = v as ZoomLevelView
        }
    }
}
