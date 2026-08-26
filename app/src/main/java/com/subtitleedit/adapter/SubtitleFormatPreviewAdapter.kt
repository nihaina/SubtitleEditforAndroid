package com.subtitleedit.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.subtitleedit.R

data class SubtitleFormatPreviewItem(
    var entryPosition: Int,
    var text: String,
    var selected: Boolean = true
)

class SubtitleFormatPreviewAdapter(
    val items: MutableList<SubtitleFormatPreviewItem>,
    private val onEditRequested: (SubtitleFormatPreviewItem, Int) -> Unit
) : RecyclerView.Adapter<SubtitleFormatPreviewAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_subtitle_format_preview, parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position], position)
    override fun getItemCount(): Int = items.size

    fun selectAll(selected: Boolean) {
        items.forEach { it.selected = selected }
        notifyDataSetChanged()
    }

    fun selectRange(startInclusive: Int, endInclusive: Int) {
        items.forEachIndexed { index, item ->
            item.selected = index in startInclusive..endInclusive
        }
        notifyDataSetChanged()
    }

    fun areAllSelected(): Boolean = items.isNotEmpty() && items.all { it.selected }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val check: CheckBox = view.findViewById(R.id.checkApplyFormat)
        private val index: TextView = view.findViewById(R.id.tvFormatIndex)
        private val text: TextView = view.findViewById(R.id.tvFormatText)
        private var boundItem: SubtitleFormatPreviewItem? = null

        init {
            check.setOnCheckedChangeListener { _, checked -> boundItem?.selected = checked }
            text.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onEditRequested(items[position], position)
            }
        }

        fun bind(item: SubtitleFormatPreviewItem, position: Int) {
            boundItem = null
            check.isChecked = item.selected
            index.text = "${position + 1}."
            text.text = item.text
            boundItem = item
        }
    }
}
