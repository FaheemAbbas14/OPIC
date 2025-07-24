package com.example.myapplication

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ZoomAdapter(
    private val zoomLevels: List<String>,
    private val onZoomSelected: (Float) -> Unit
) : RecyclerView.Adapter<ZoomAdapter.ZoomViewHolder>() {

    private var selectedPosition = -1

    inner class ZoomViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textView: TextView = view.findViewById(R.id.zoomText)
        val textViewOpic: OpicTextView1 = view.findViewById(R.id.opiczoomText)
        val tickLine: View = view.findViewById(R.id.tickLine)
        val selectedDot: View = view.findViewById(R.id.selectedDot)
    }

    private fun dpToPx(context: Context, dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ZoomViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_zoom_level, parent, false)
        return ZoomViewHolder(view)
    }

    override fun onBindViewHolder(holder: ZoomViewHolder, position: Int) {
        val zoomText = zoomLevels[position]
        val zoomLabel = "$zoomText"

        if (position == selectedPosition) {
            holder.textView.visibility = View.GONE
            holder.textViewOpic.visibility = View.VISIBLE
            holder.textViewOpic.timerText = zoomLabel

            holder.tickLine.layoutParams.width = dpToPx(holder.tickLine.context, 12)
            holder.tickLine.layoutParams.height = dpToPx(holder.tickLine.context, 2)
            holder.tickLine.setBackgroundResource(R.drawable.viewlinecolored)
            holder.selectedDot.visibility = View.VISIBLE
        } else {
            holder.textView.visibility = View.VISIBLE
            holder.textViewOpic.visibility = View.GONE
            holder.textView.text = zoomLabel
            holder.textView.setTextColor(Color.parseColor("#666666"))
            holder.textView.setTypeface(null, Typeface.NORMAL)
            holder.textView.textSize = 14f

            holder.tickLine.layoutParams.width = dpToPx(holder.tickLine.context, 12)
            holder.tickLine.layoutParams.height = dpToPx(holder.tickLine.context, 1)
            holder.selectedDot.visibility = View.GONE
        }

        holder.tickLine.requestLayout()

        holder.itemView.setOnClickListener {
            selectedPosition = position
            notifyDataSetChanged()
            onZoomSelected(zoomText.toFloat())
        }
    }

    override fun getItemCount() = zoomLevels.size

    fun setSelectedZoom(zoom: Float) {
        selectedPosition = zoomLevels.indexOfFirst {
            it.removeSuffix("x").toFloat() == zoom
        }
        notifyDataSetChanged()
    }
}
