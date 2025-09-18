// CameraModeAdapter.kt
package com.example.myapplication.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.ColorInt
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.example.myapplication.views.OpicTextView

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
        val tv: OpicTextView = itemView.findViewById(R.id.tvMode)
        val chip: View = itemView.findViewById(R.id.modeChip) // NEW: container with rounded bg
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
        holder.tv.timerText = label

        // NEW: switch rounded transparent background based on selection
        val selected = position == selectedPosition
        holder.chip.setBackgroundResource(
            if (selected) R.drawable.bg_mode_selected else R.drawable.bg_mode_unselected
        )

        // Optional: a subtle scale cue
        holder.tv.scaleX = if (selected) 1.08f else 1.0f
        holder.tv.scaleY = if (selected) 1.08f else 1.0f
    }

    override fun getItemCount(): Int = modes.size
}
