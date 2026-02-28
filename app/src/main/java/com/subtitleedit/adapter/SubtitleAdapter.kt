package com.subtitleedit.adapter

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.subtitleedit.R
import com.subtitleedit.SubtitleEntry
import com.subtitleedit.util.TimeUtils

/**
 * 字幕列表适配器
 * 支持点击编辑、长按菜单、多选功能
 */
class SubtitleAdapter(
    private val onItemClick: (SubtitleEntry, Int) -> Unit,
    private val onItemLongClick: (SubtitleEntry, Int) -> Unit,
    private val onTimeClick: (SubtitleEntry, Int, Boolean) -> Unit, // isStartTime
    private val onTextClick: (SubtitleEntry, Int) -> Unit
) : ListAdapter<SubtitleEntry, SubtitleAdapter.SubtitleViewHolder>(SubtitleDiffCallback()) {

    private val selectedPositions = mutableSetOf<Int>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SubtitleViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_subtitle, parent, false)
        return SubtitleViewHolder(view)
    }

    override fun onBindViewHolder(holder: SubtitleViewHolder, position: Int) {
        holder.bind(getItem(position), position, isSelected(position))
    }
    
    override fun onBindViewHolder(holder: SubtitleViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_SELECTION)) {
            // 只刷新选中状态，不刷新整个条目
            holder.bindSelection(isSelected(position))
        } else {
            onBindViewHolder(holder, position)
        }
    }

    fun toggleSelection(position: Int) {
        if (isSelected(position)) {
            selectedPositions.remove(position)
        } else {
            selectedPositions.add(position)
        }
        // 使用 payload 强制刷新选中状态
        notifyItemChanged(position, PAYLOAD_SELECTION)
    }
    
    companion object {
        const val PAYLOAD_SELECTION = "selection"
    }

    fun isSelected(position: Int): Boolean {
        return selectedPositions.contains(position)
    }

    fun getSelectedPositions(): Set<Int> {
        return selectedPositions.toSet()
    }

    fun getSelectedEntries(): List<Pair<SubtitleEntry, Int>> {
        return selectedPositions.sorted().mapNotNull { position ->
            if (position < getItemCount()) {
                Pair(getItem(position), position)
            } else null
        }
    }

    fun clearSelection() {
        val positionsToNotify = selectedPositions.toList()
        selectedPositions.clear()
        positionsToNotify.forEach { position ->
            notifyItemChanged(position)
        }
    }

    fun getSelectedCount(): Int {
        return selectedPositions.size
    }

    /**
     * 强制刷新所有可见项（用于行数序列号实时更新）
     */
    fun refreshAllItems() {
        notifyDataSetChanged()
    }
    
    // 搜索高亮相关
    private var searchHighlightPosition: Int = -1
    private var searchQuery: String = ""
    
    /**
     * 高亮显示搜索结果
     */
    fun highlightSearchResult(position: Int, query: String) {
        searchHighlightPosition = position
        searchQuery = query
        notifyDataSetChanged()
    }
    
    /**
     * 清除搜索高亮
     */
    fun clearSearchHighlight() {
        searchHighlightPosition = -1
        searchQuery = ""
        notifyDataSetChanged()
    }

    inner class SubtitleViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvIndex: TextView = itemView.findViewById(R.id.tvIndex)
        private val tvStartTime: TextView = itemView.findViewById(R.id.tvStartTime)
        private val tvEndTime: TextView = itemView.findViewById(R.id.tvEndTime)
        private val tvSubtitleText: TextView = itemView.findViewById(R.id.tvSubtitleText)
        private val ivSelected: ImageView = itemView.findViewById(R.id.ivSelected)

        /**
         * 只刷新选中状态（用于 payload 刷新）
         */
        fun bindSelection(isSelected: Boolean) {
            ivSelected.visibility = if (isSelected) View.VISIBLE else View.GONE
            itemView.alpha = if (isSelected) 0.6f else 1.0f
        }

        fun bind(entry: SubtitleEntry, position: Int, isSelected: Boolean) {
            // 设置序号
            tvIndex.text = entry.index.toString()

            // 设置时间轴
            tvStartTime.text = TimeUtils.formatForInput(entry.startTime)
            tvEndTime.text = TimeUtils.formatForInput(entry.endTime)

            // 设置字幕文本（只显示第一行）
            val displayText = entry.text.split("\n").firstOrNull() ?: entry.text
            
            // 检查是否需要高亮搜索
            if (position == searchHighlightPosition && searchQuery.isNotEmpty()) {
                // 高亮整个条目背景
                itemView.setBackgroundColor(ContextCompat.getColor(itemView.context, R.color.primary_container))
                
                // 高亮文本中的搜索词
                if (displayText.contains(searchQuery, ignoreCase = true)) {
                    val spannable = SpannableString(displayText)
                    val startIndex = displayText.indexOf(searchQuery, ignoreCase = true)
                    val endIndex = startIndex + searchQuery.length
                    spannable.setSpan(
                        BackgroundColorSpan(ContextCompat.getColor(itemView.context, R.color.inverse_primary)),
                        startIndex,
                        endIndex,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    spannable.setSpan(
                        StyleSpan(Typeface.BOLD),
                        startIndex,
                        endIndex,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    tvSubtitleText.text = spannable
                } else {
                    tvSubtitleText.text = displayText
                }
            } else {
                // 恢复正常背景
                itemView.setBackgroundColor(ContextCompat.getColor(itemView.context, android.R.color.transparent))
                tvSubtitleText.text = displayText
            }

            // 设置选中状态
            ivSelected.visibility = if (isSelected) View.VISIBLE else View.GONE
            itemView.alpha = if (isSelected) 0.6f else 1.0f

            // 点击事件 - 切换选中状态
            // 使用 position 参数而不是 adapterPosition，因为 position 更可靠
            itemView.setOnClickListener {
                toggleSelection(position)
            }

            // 长按事件 - 弹出菜单（不自动选中）
            itemView.setOnLongClickListener {
                val adapterPosition = adapterPosition
                if (adapterPosition != RecyclerView.NO_POSITION) {
                    onItemLongClick(entry, adapterPosition)
                    true
                } else {
                    false
                }
            }

            // 时间点击事件 - 编辑时间
            tvStartTime.setOnClickListener {
                val adapterPosition = adapterPosition
                if (adapterPosition != RecyclerView.NO_POSITION) {
                    onTimeClick(entry, adapterPosition, true)
                }
            }

            tvEndTime.setOnClickListener {
                val adapterPosition = adapterPosition
                if (adapterPosition != RecyclerView.NO_POSITION) {
                    onTimeClick(entry, adapterPosition, false)
                }
            }

            // 文本点击事件 - 编辑文本
            tvSubtitleText.setOnClickListener {
                val adapterPosition = adapterPosition
                if (adapterPosition != RecyclerView.NO_POSITION) {
                    onTextClick(entry, adapterPosition)
                }
            }
        }
    }

    private class SubtitleDiffCallback : DiffUtil.ItemCallback<SubtitleEntry>() {
        override fun areItemsTheSame(oldItem: SubtitleEntry, newItem: SubtitleEntry): Boolean {
            // 使用对象的 identity 而不是 index，因为 index 会在插入/删除时变化
            return oldItem === newItem
        }

        override fun areContentsTheSame(oldItem: SubtitleEntry, newItem: SubtitleEntry): Boolean {
            return oldItem.index == newItem.index &&
                   oldItem.startTime == newItem.startTime &&
                   oldItem.endTime == newItem.endTime &&
                   oldItem.text == newItem.text
        }
    }
}
