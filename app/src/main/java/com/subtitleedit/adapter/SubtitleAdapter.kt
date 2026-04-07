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
 * 
 * 使用 SubtitleEntry 对象本身（而非 position）来跟踪选中状态，
 * 这样在数据变化时选中状态不会错位
 */
class SubtitleAdapter(
    private val onItemClick: (SubtitleEntry, Int) -> Unit,
    private val onItemLongClick: (SubtitleEntry, Int) -> Unit,
    private val onTimeClick: (SubtitleEntry, Int, Boolean) -> Unit, // isStartTime
    private val onTextClick: (SubtitleEntry, Int) -> Unit,
    private val onJumpToTimeClick: (SubtitleEntry, Int) -> Unit, // 跳转到字幕时间
    private val onSetTimeClick: (SubtitleEntry, Int) -> Unit, // 设置字幕时间为当前进度
    private val isAudioFile: Boolean = false, // 是否为音频文件模式
    private val onSelectionChanged: (() -> Unit)? = null // 选中状态变化回调
) : ListAdapter<SubtitleEntry, SubtitleAdapter.SubtitleViewHolder>(SubtitleDiffCallback()) {

    // 使用对象本身来跟踪选中状态，而不是 position
    // 这样在数据变化时选中状态不会错位
    private val selectedEntries = mutableSetOf<SubtitleEntry>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SubtitleViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_subtitle, parent, false)
        return SubtitleViewHolder(view)
    }

    override fun onBindViewHolder(holder: SubtitleViewHolder, position: Int) {
        holder.bind(getItem(position), position, isSelected(position))
    }
    
    override fun onBindViewHolder(holder: SubtitleViewHolder, position: Int, payloads: MutableList<Any>) {
        when {
            payloads.contains(PAYLOAD_PLAYING) -> {
                // 只更新播放高亮背景，完全不触碰选中状态（alpha / ivSelected）
                holder.bindPlaying(position == currentPlayingPosition)
            }
            payloads.contains(PAYLOAD_SELECTION) -> {
                holder.bindSelection(isSelected(position))
            }
            else -> onBindViewHolder(holder, position)
        }
    }

    fun toggleSelection(position: Int) {
        val entry = getItem(position)
        if (isSelected(position)) {
            selectedEntries.remove(entry)
        } else {
            selectedEntries.add(entry)
        }
        // 使用 payload 强制刷新选中状态
        notifyItemChanged(position, PAYLOAD_SELECTION)
        // 通知选中状态变化
        onSelectionChanged?.invoke()
    }
    
    companion object {
        const val PAYLOAD_SELECTION = "selection"
        const val PAYLOAD_PLAYING   = "playing"
    }

    fun isSelected(position: Int): Boolean {
        val entry = getItem(position)
        return selectedEntries.contains(entry)
    }

    fun getSelectedPositions(): Set<Int> {
        // 返回当前选中的 position 列表（用于通知刷新）
        return selectedEntries.mapNotNull { entry ->
            val index = currentList.indexOf(entry)
            if (index >= 0) index else null
        }.toSet()
    }

    fun getSelectedEntries(): List<Pair<SubtitleEntry, Int>> {
        // 返回选中的条目及其当前位置
        return selectedEntries.mapNotNull { entry ->
            val index = currentList.indexOf(entry)
            if (index >= 0) Pair(entry, index) else null
        }.sortedBy { it.second }
    }

    fun clearSelection() {
        // 获取所有需要刷新的位置
        val positionsToNotify = getSelectedPositions()
        selectedEntries.clear()
        positionsToNotify.forEach { position ->
            notifyItemChanged(position)
        }
    }

    fun getSelectedCount(): Int {
        return selectedEntries.size
    }
    
    /**
     * 根据条目对象移除选中状态（用于删除操作后保持其他选中状态）
     */
    fun removeSelectionByEntry(entry: SubtitleEntry) {
        selectedEntries.remove(entry)
    }
    
    /**
     * 数据变化后刷新选中状态（重新计算位置）
     * 关键：使用 currentList 中的对象引用来更新 selectedEntries
     */
    fun refreshSelectionAfterDataChange() {
        // 保存当前选中的条目数据（用于匹配）
        val selectedData = selectedEntries.map { it.copy() }.toSet()
        selectedEntries.clear()
        
        // 从 currentList 中找到匹配的条目，使用 currentList 中的引用
        currentList.forEach { entry ->
            val entryData = entry.copy()
            if (selectedData.contains(entryData)) {
                selectedEntries.add(entry)
            }
        }
        
        notifyDataSetChanged()
    }
    
    /**
     * 根据数据匹配重新同步选中状态
     * 用于在 submitList 后保持选中状态
     */
    fun syncSelectionWithCurrentList() {
        // 保存当前选中的条目数据（用于匹配）
        val selectedData = selectedEntries.map { 
            Triple(it.startTime, it.endTime, it.text) 
        }.toSet()
        selectedEntries.clear()
        
        // 从 currentList 中找到匹配的条目，使用 currentList 中的引用
        currentList.forEach { entry ->
            val key = Triple(entry.startTime, entry.endTime, entry.text)
            if (selectedData.contains(key)) {
                selectedEntries.add(entry)
            }
        }
        
        notifyDataSetChanged()
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
    
    // 当前播放的字幕位置（用于音频播放时高亮）
    private var currentPlayingPosition: Int = -1
    
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
    
    /**
     * 高亮显示当前正在播放的字幕
     */
    fun highlightCurrentPlaying(position: Int) {
        val oldPosition = currentPlayingPosition
        currentPlayingPosition = position
        if (oldPosition >= 0) notifyItemChanged(oldPosition, PAYLOAD_PLAYING)
        if (position >= 0)    notifyItemChanged(position,    PAYLOAD_PLAYING)
    }
    
    /**
     * 清除播放高亮
     */
    fun clearPlayingHighlight() {
        val oldPosition = currentPlayingPosition
        currentPlayingPosition = -1
        if (oldPosition >= 0) notifyItemChanged(oldPosition, PAYLOAD_PLAYING)
    }
    
    /**
     * 检查当前字幕的结束时间是否超过下一行字幕的起始时间
     * （LRC 格式冲突检测）
     */
    private fun hasTimeConflict(position: Int, entry: SubtitleEntry): Boolean {
        // 如果不是最后一行，检查结束时间是否超过下一行的起始时间
        if (position < currentList.size - 1) {
            val nextEntry = currentList[position + 1]
            // 如果结束时间超过下一行起始时间，则标记为冲突
            if (entry.endTime > nextEntry.startTime) {
                return true
            }
        }
        return false
    }

    inner class SubtitleViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvIndex: TextView = itemView.findViewById(R.id.tvIndex)
        private val tvStartTime: TextView = itemView.findViewById(R.id.tvStartTime)
        private val tvEndTime: TextView = itemView.findViewById(R.id.tvEndTime)
        private val tvSubtitleText: TextView = itemView.findViewById(R.id.tvSubtitleText)
        private val ivSelected: ImageView = itemView.findViewById(R.id.ivSelected)
        private val btnJumpToTime: ImageView = itemView.findViewById(R.id.btnJumpToTime)
        private val btnSetTime: ImageView = itemView.findViewById(R.id.btnSetTime)

        /**
         * 只刷新选中状态（用于 payload 刷新）
         */
        fun bindSelection(isSelected: Boolean) {
            ivSelected.visibility = if (isSelected) View.VISIBLE else View.GONE
            itemView.alpha = if (isSelected) 0.6f else 1.0f
        }

        /**
         * 只刷新播放高亮背景（用于 payload 刷新）
         */
        fun bindPlaying(isPlaying: Boolean) {
            itemView.setBackgroundColor(
                if (isPlaying)
                    ContextCompat.getColor(itemView.context, R.color.playing_highlight)
                else
                    android.graphics.Color.TRANSPARENT
            )
        }

        fun bind(entry: SubtitleEntry, position: Int, isSelected: Boolean) {
            // 设置序号
            tvIndex.text = entry.index.toString()

            // 设置时间轴
            tvStartTime.text = TimeUtils.formatForInput(entry.startTime)
            tvEndTime.text = TimeUtils.formatForInput(entry.endTime)

            // 检查时间是否有效
            // 1. 开始时间大于等于结束时间 - 两个时间都标红
            // 2. 结束时间超过下一行字幕的起始时间（LRC 格式冲突检测）- 只标红结束时间
            val isBasicInvalid = entry.startTime >= entry.endTime
            val hasConflict = hasTimeConflict(position, entry)
            
            if (isBasicInvalid) {
                // 开始时间 >= 结束时间，两个时间都标红
                val errorColor = ContextCompat.getColor(itemView.context, R.color.error)
                tvStartTime.setTextColor(errorColor)
                tvEndTime.setTextColor(errorColor)
            } else if (hasConflict) {
                // 结束时间超过下一行起始时间，只标红结束时间
                val normalColor = ContextCompat.getColor(itemView.context, R.color.primary)
                val errorColor = ContextCompat.getColor(itemView.context, R.color.error)
                tvStartTime.setTextColor(normalColor)
                tvEndTime.setTextColor(errorColor)
            } else {
                // 恢复正常颜色
                val normalColor = ContextCompat.getColor(itemView.context, R.color.primary)
                tvStartTime.setTextColor(normalColor)
                tvEndTime.setTextColor(normalColor)
            }

            // 根据是否为音频文件模式控制按钮显示
            btnJumpToTime.visibility = if (isAudioFile) View.VISIBLE else View.GONE
            btnSetTime.visibility = if (isAudioFile) View.VISIBLE else View.GONE

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
            } else if (position == currentPlayingPosition) {
                // 高亮当前播放的字幕（使用淡黄色，避免遮挡时间轴）
                itemView.setBackgroundColor(ContextCompat.getColor(itemView.context, R.color.playing_highlight))
                tvSubtitleText.text = displayText
            } else {
                // 恢复正常背景
                itemView.setBackgroundColor(ContextCompat.getColor(itemView.context, android.R.color.transparent))
                tvSubtitleText.text = displayText
            }

            // 设置选中状态
            ivSelected.visibility = if (isSelected) View.VISIBLE else View.GONE
            itemView.alpha = if (isSelected) 0.6f else 1.0f

            // 点击事件 - 切换选中状态
            // 使用 adapterPosition 获取实时的位置，避免 ViewHolder 复用时 position 过期
            itemView.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    toggleSelection(pos)
                }
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

            // 右上按钮：跳转到字幕时间
            btnJumpToTime.setOnClickListener {
                val adapterPosition = adapterPosition
                if (adapterPosition != RecyclerView.NO_POSITION) {
                    onJumpToTimeClick(entry, adapterPosition)
                }
            }

            // 右下按钮：设置字幕时间为当前进度
            btnSetTime.setOnClickListener {
                val adapterPosition = adapterPosition
                if (adapterPosition != RecyclerView.NO_POSITION) {
                    onSetTimeClick(entry, adapterPosition)
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
