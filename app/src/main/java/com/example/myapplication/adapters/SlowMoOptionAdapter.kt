package com.example.myapplication.adapters


/**
 * Created by Faheem Abbas on 22/09/2025.
 * Technical Lead
 * Bajco Technologies
 * faheem.abbas@bajcotechnologies.com
 * +923115284424
 */

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import com.example.myapplication.R
import com.example.myapplication.model.SlowMoOption

class SlowMoOptionAdapter(
    context: Context,
    private val items: List<SlowMoOption>
) : ArrayAdapter<SlowMoOption>(context, 0, items) {

    private val inflater = LayoutInflater.from(context)

    override fun getCount() = items.size

    override fun getItem(position: Int): SlowMoOption? = items[position]

    // Collapsed/selected view (Spinner shows this in the bar)
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: inflater.inflate(R.layout.item_slowmo_selected, parent, false)
        bindSelected(view, items[position])
        return view
    }

    // Dropdown rows
    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: inflater.inflate(R.layout.item_slowmo_dropdown, parent, false)
        bindDropdown(view, items[position], isSelected = position == (parent as? android.widget.Spinner)?.selectedItemPosition)
        return view
    }

    private fun bindSelected(view: View, item: SlowMoOption) {
        val tvMain = view.findViewById<TextView>(R.id.tvMain)
        val tvSub  = view.findViewById<TextView>(R.id.tvSub)
        val ivIcon = view.findViewById<ImageView>(R.id.ivIcon)

        tvMain.text = "${item.size.width}×${item.size.height}"
        tvSub.text  = "${item.fpsRange.upper} fps"
        ivIcon.setImageResource(R.drawable.ic_slowmo) // your vector
    }

    private fun bindDropdown(view: View, item: SlowMoOption, isSelected: Boolean) {
        val tvMain = view.findViewById<TextView>(R.id.tvMain)
        val tvSub  = view.findViewById<TextView>(R.id.tvSub)
        val ivIcon = view.findViewById<ImageView>(R.id.ivIcon)
        val ivCheck = view.findViewById<ImageView>(R.id.ivCheck)

        tvMain.text = "${item.size.width}×${item.size.height}"
        tvSub.text  = "${item.fpsRange.upper} fps"
        ivIcon.setImageResource(R.drawable.ic_slowmo)
        ivCheck.isVisible = isSelected
    }
}
