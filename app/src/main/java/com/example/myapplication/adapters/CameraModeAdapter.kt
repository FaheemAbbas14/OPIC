// CameraModeAdapter.kt
package com.example.myapplication.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R

class CameraModeAdapter(
    private val modes: List<String>,
    @ColorInt private val selectedColor: Int,
    @ColorInt private val unselectedColor: Int
) : RecyclerView.Adapter<CameraModeAdapter.VH>() {

    var selectedPosition: Int = 0
        set(value) {
            if (field == value) return
            val old = field
            field = value
            notifyItemChanged(old)
            notifyItemChanged(field)
        }

    var onModeSelected: ((position: Int, label: String) -> Unit)? = null

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tv: TextView = itemView.findViewById(R.id.tvMode)
        init {
            itemView.setOnClickListener {
                onModeSelected?.invoke(bindingAdapterPosition, modes[bindingAdapterPosition])
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_camera_mode, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val label = modes[position]
        holder.tv.text = label
        holder.tv.setTextColor(if (position == selectedPosition) selectedColor else unselectedColor)
        holder.tv.paint.isFakeBoldText = position == selectedPosition
        holder.tv.scaleX = if (position == selectedPosition) 1.1f else 1.0f
        holder.tv.scaleY = if (position == selectedPosition) 1.1f else 1.0f
    }

    override fun getItemCount(): Int = modes.size
}
