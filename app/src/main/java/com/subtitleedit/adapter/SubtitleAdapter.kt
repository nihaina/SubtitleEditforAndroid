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
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SearchTextMatcher
import com.subtitleedit.util.SubtitleTimeConflict
import com.subtitleedit.util.TimeUtils
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale

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
    private var hasPlayableMedia: Boolean = false,
    private val onSelectionChanged: (() -> Unit)? = null // 选中状态变化回调
) : ListAdapter<SubtitleEntry, SubtitleAdapter.SubtitleViewHolder>(SubtitleDiffCallback()) {

    fun setHasPlayableMedia(value: Boolean) {
        if (hasPlayableMedia == value) return
        hasPlayableMedia = value
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
    }

    // 使用对象引用跟踪选中状态，而非 data class 的可变字段哈希值。
    // 翻译、转录或编辑文本后，条目仍保持原有选中状态。
    private val selectedEntries: MutableSet<SubtitleEntry> =
        Collections.newSetFromMap(IdentityHashMap())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SubtitleViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_subtitle, parent, false)
        return SubtitleViewHolder(view)
    }

    override fun onBindViewHolder(holder: SubtitleViewHolder, position: Int) {
        holder.bind(getItem(position), position, isSelected(position))
    }
    
    override fun onBindViewHolder(holder: SubtitleViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty() || payloads.any {
                it != PAYLOAD_PLAYING && it != PAYLOAD_SELECTION && it != PAYLOAD_TIME_CONFLICT
            }) {
            onBindViewHolder(holder, position)
            return
        }
        if (PAYLOAD_TIME_CONFLICT in payloads) holder.bindTimeColors(currentList, position)
        if (PAYLOAD_PLAYING in payloads) holder.bindPlaying(position == currentPlayingPosition)
        if (PAYLOAD_SELECTION in payloads) holder.bindSelection(isSelected(position))
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
        const val PAYLOAD_TIME_CONFLICT = "time_conflict"
        private const val SELECTION_REFRESH_THRESHOLD = 200
    }

    private fun notifyAllItemsChanged(payload: Any? = null) {
        if (itemCount == 0) return
        if (payload == null) notifyItemRangeChanged(0, itemCount)
        else notifyItemRangeChanged(0, itemCount, payload)
    }

    fun isSelected(position: Int): Boolean {
        val entry = getItem(position)
        return selectedEntries.contains(entry)
    }

    fun getSelectedPositions(): Set<Int> {
        // 单次扫描当前列表。逐个对 selectedEntries 调用 indexOfFirst 会在全选大文件时退化为 O(n²)。
        return currentList.mapIndexedNotNullTo(mutableSetOf()) { index, entry ->
            index.takeIf { selectedEntries.contains(entry) }
        }
    }

    fun getSelectedEntries(): List<Pair<SubtitleEntry, Int>> {
        // 当前列表本身已经按字幕顺序排列，单次扫描即可得到有序结果。
        return currentList.mapIndexedNotNull { index, entry ->
            if (selectedEntries.contains(entry)) Pair(entry, index) else null
        }
    }

    fun clearSelection() {
        val positionsToNotify = getSelectedPositions()
        val selectedCount = selectedEntries.size
        selectedEntries.clear()
        if (selectedCount == 0) return
        if (selectedCount > SELECTION_REFRESH_THRESHOLD) {
            notifyAllItemsChanged(PAYLOAD_SELECTION)
        } else {
            positionsToNotify.forEach { position ->
                notifyItemChanged(position, PAYLOAD_SELECTION)
            }
        }
    }

    fun setSelectionByIndices(indices: Set<Int>) {
        val oldPositions = getSelectedPositions()
        selectedEntries.clear()
        indices.forEach { idx ->
            if (idx >= 0 && idx < currentList.size) {
                selectedEntries.add(currentList[idx])
            }
        }
        val changedCount = oldPositions.size + indices.size
        if (changedCount > SELECTION_REFRESH_THRESHOLD) {
            notifyAllItemsChanged(PAYLOAD_SELECTION)
        } else {
            // 刷新旧的和新的选中位置
            (oldPositions.asSequence() + indices.asSequence()).forEach { position ->
                if (position >= 0 && position < currentList.size) {
                    notifyItemChanged(position, PAYLOAD_SELECTION)
                }
            }
        }
    }

    fun setSelectionByStableIds(ids: Set<Long>) {
        setSelectionByIndices(
            currentList.mapIndexedNotNull { index, entry ->
                index.takeIf { entry.stableId in ids }
            }.toSet()
        )
    }

    /** Selects or clears the complete list without allocating an index set. */
    fun setAllSelection(selected: Boolean) {
        if (selected) {
            currentList.forEach { selectedEntries.add(it) }
        } else {
            selectedEntries.clear()
        }
        notifyAllItemsChanged(PAYLOAD_SELECTION)
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
        
        notifyAllItemsChanged(PAYLOAD_SELECTION)
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
        
        notifyAllItemsChanged(PAYLOAD_SELECTION)
    }

    /**
     * 强制刷新所有可见项（用于行数序列号实时更新）
     */
    fun refreshAllItems() {
        notifyAllItemsChanged()
    }

    /** Recheck attached rows against the live document, including edits made outside the list. */
    fun refreshVisibleTimeConflicts(recyclerView: RecyclerView, entries: List<SubtitleEntry>) {
        if (entries.size != itemCount) return
        for (childIndex in 0 until recyclerView.childCount) {
            val holder = recyclerView.getChildViewHolder(recyclerView.getChildAt(childIndex))
                as? SubtitleViewHolder ?: continue
            val position = holder.bindingAdapterPosition
            if (position !in currentList.indices ||
                currentList[position].stableId != entries[position].stableId
            ) continue
            holder.bindTimeColors(entries, position)
        }
    }
    
    // 搜索高亮相关
    private var searchHighlightPosition: Int = -1
    private var searchQuery: String = ""
    private var searchMatchCase: Boolean = false
    private var searchWholeWord: Boolean = false
    
    // 当前播放的字幕位置（用于音频播放时高亮）
    private var currentPlayingPosition: Int = -1
    
    /**
     * 高亮显示搜索结果
     */
    fun highlightSearchResult(
        position: Int,
        query: String,
        matchCase: Boolean = false,
        wholeWord: Boolean = false
    ) {
        searchHighlightPosition = position
        searchQuery = query
        searchMatchCase = matchCase
        searchWholeWord = wholeWord
        notifyAllItemsChanged()
    }
    
    /**
     * 清除搜索高亮
     */
    fun clearSearchHighlight() {
        searchHighlightPosition = -1
        searchQuery = ""
        searchMatchCase = false
        searchWholeWord = false
        notifyAllItemsChanged()
    }
    
    /**
     * 高亮显示当前正在播放的字幕
     */
    fun highlightCurrentPlaying(position: Int) {
        val oldPosition = currentPlayingPosition
        if (oldPosition == position) return
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
    
    inner class SubtitleViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val contentContainer: View = (itemView as ViewGroup).getChildAt(0)
        private val tvIndex: TextView = itemView.findViewById(R.id.tvIndex)
        private val tvStartTime: TextView = itemView.findViewById(R.id.tvStartTime)
        private val tvEndTime: TextView = itemView.findViewById(R.id.tvEndTime)
        private val tvFormatMetadata: TextView = itemView.findViewById(R.id.tvFormatMetadata)
        private val tvSubtitleText: TextView = itemView.findViewById(R.id.tvSubtitleText)
        private val ivSelected: ImageView = itemView.findViewById(R.id.ivSelected)
        private val btnJumpToTime: ImageView = itemView.findViewById(R.id.btnJumpToTime)
        private val btnSetTime: ImageView = itemView.findViewById(R.id.btnSetTime)

        fun bindTimeColors(entries: List<SubtitleEntry>, position: Int) {
            val markers = SubtitleTimeConflict.markers(entries, position)
            val normalColor = ContextCompat.getColor(itemView.context, R.color.primary)
            val errorColor = ContextCompat.getColor(itemView.context, R.color.error)
            val startColor = if (markers.start) errorColor else normalColor
            val endColor = if (markers.end) errorColor else normalColor
            if (tvStartTime.currentTextColor != startColor) tvStartTime.setTextColor(startColor)
            if (tvEndTime.currentTextColor != endColor) tvEndTime.setTextColor(endColor)
        }

        /**
         * 只刷新选中状态（用于 payload 刷新）
         */
        fun bindSelection(isSelected: Boolean) {
            ivSelected.visibility = if (isSelected) View.VISIBLE else View.GONE
            contentContainer.alpha = if (isSelected) 0.6f else 1.0f
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
            tvIndex.text = String.format(Locale.getDefault(), "%d", entry.index)

            // 设置时间轴
            tvStartTime.text = TimeUtils.formatForInput(entry.startTime)
            tvEndTime.text = TimeUtils.formatForInput(entry.endTime)
            val formatMetadata = buildList {
                if (entry.cueIdentifier.isNotBlank()) add("ID: ${entry.cueIdentifier}")
                if (entry.cueSettings.isNotBlank()) add(entry.cueSettings)
            }.joinToString(" · ")
            tvFormatMetadata.text = formatMetadata
            tvFormatMetadata.visibility = if (formatMetadata.isBlank()) View.GONE else View.VISIBLE

            bindTimeColors(currentList, position)

            // 根据是否为音频文件模式控制按钮显示
            btnJumpToTime.visibility = if (hasPlayableMedia) View.VISIBLE else View.GONE
            btnSetTime.visibility = if (hasPlayableMedia) View.VISIBLE else View.GONE

            // 保留字幕中的换行，由 TextView 的 maxLines 限制列表预览高度。
            val displayText = entry.text
            
            // 检查是否需要高亮搜索
            if (position == searchHighlightPosition && searchQuery.isNotEmpty()) {
                // 高亮整个条目背景
                itemView.setBackgroundColor(ContextCompat.getColor(itemView.context, R.color.primary_container))
                
                // 高亮文本中的搜索词
                val matchRange = SearchTextMatcher.firstMatchRange(
                    displayText,
                    searchQuery,
                    searchMatchCase,
                    searchWholeWord
                )
                if (matchRange != null) {
                    val spannable = SpannableString(displayText)
                    spannable.setSpan(
                        BackgroundColorSpan(ContextCompat.getColor(itemView.context, R.color.inverse_primary)),
                        matchRange.first,
                        matchRange.last + 1,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    spannable.setSpan(
                        StyleSpan(Typeface.BOLD),
                        matchRange.first,
                        matchRange.last + 1,
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
            contentContainer.alpha = if (isSelected) 0.6f else 1.0f

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
            return oldItem.stableId == newItem.stableId
        }

        override fun areContentsTheSame(oldItem: SubtitleEntry, newItem: SubtitleEntry): Boolean {
            return oldItem.index == newItem.index &&
                   oldItem.startTime == newItem.startTime &&
                   oldItem.endTime == newItem.endTime &&
                   oldItem.text == newItem.text &&
                   oldItem.cueIdentifier == newItem.cueIdentifier &&
                   oldItem.cueSettings == newItem.cueSettings
        }
    }
}
