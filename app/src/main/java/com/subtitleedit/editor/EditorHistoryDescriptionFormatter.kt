package com.subtitleedit.editor

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.EditorEditHistory
import com.subtitleedit.util.TimeUtils

/** 生成编辑历史记录的可读描述，不依赖 Activity 或视图状态。 */
internal object EditorHistoryDescriptionFormatter {
    fun describeListStateChange(difference: EditorEditHistory.ListDifference): String {
        val descriptions = mutableListOf<String>()
        difference.deleted.forEach { entry ->
            descriptions += "删除${entry.stableId}字幕［${formatHistoryTime(entry)}］${entry.text}"
        }
        difference.added.forEach { entry ->
            descriptions += "新增${entry.stableId}字幕［${formatHistoryTime(entry)}］${entry.text}"
        }
        difference.modified.forEach { (old, _) ->
            descriptions += "修改${old.stableId}字幕［${formatHistoryTime(old)}］${old.text}"
        }
        difference.selected.forEach { id -> descriptions += "选中${id}字幕" }
        difference.deselected.forEach { id -> descriptions += "取消选中${id}字幕" }
        if (difference.orderChanged && difference.deleted.isEmpty() && difference.added.isEmpty()) {
            descriptions += "调整字幕顺序"
        }
        return descriptions.joinToString("\n")
    }

    fun describeSourceTextChange(before: String, after: String): String {
        val beforeLines = before.split('\n')
        val afterLines = after.split('\n')
        return (0 until maxOf(beforeLines.size, afterLines.size))
            .filter { index -> beforeLines.getOrNull(index) != afterLines.getOrNull(index) }
            .joinToString("\n") { index ->
                "修改${index + 1}行 修改前${beforeLines.getOrNull(index).orEmpty().removeSuffix("\r")}"
            }
    }

    private fun formatHistoryTime(entry: SubtitleEntry): String =
        "${TimeUtils.formatForInput(entry.startTime)}-${TimeUtils.formatForInput(entry.endTime)}"
}
