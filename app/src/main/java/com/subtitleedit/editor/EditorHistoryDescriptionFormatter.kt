package com.subtitleedit.editor

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.EditorEditHistory
import com.subtitleedit.util.TimeUtils

/** 生成编辑历史记录的可读描述，不依赖 Activity 或视图状态。 */
internal object EditorHistoryDescriptionFormatter {
    fun describeListStateChange(difference: EditorEditHistory.ListDifference): String {
        val descriptions = mutableListOf<String>()
        difference.deleted.take(MAX_DESCRIPTION_LINES).forEach { entry ->
            descriptions += "删除${entry.stableId}字幕［${formatHistoryTime(entry)}］${shortText(entry.text)}"
        }
        difference.added.take((MAX_DESCRIPTION_LINES - descriptions.size).coerceAtLeast(0)).forEach { entry ->
            descriptions += "新增${entry.stableId}字幕［${formatHistoryTime(entry)}］${shortText(entry.text)}"
        }
        difference.modified.take((MAX_DESCRIPTION_LINES - descriptions.size).coerceAtLeast(0)).forEach { (old, _) ->
            descriptions += "修改${old.stableId}字幕［${formatHistoryTime(old)}］${shortText(old.text)}"
        }
        difference.selected.take((MAX_DESCRIPTION_LINES - descriptions.size).coerceAtLeast(0))
            .forEach { id -> descriptions += "选中${id}字幕" }
        difference.deselected.take((MAX_DESCRIPTION_LINES - descriptions.size).coerceAtLeast(0))
            .forEach { id -> descriptions += "取消选中${id}字幕" }
        if (difference.orderChanged && difference.deleted.isEmpty() && difference.added.isEmpty() &&
            descriptions.size < MAX_DESCRIPTION_LINES
        ) {
            descriptions += "调整字幕顺序"
        }
        val changeCount = difference.deleted.size + difference.added.size + difference.modified.size +
            difference.selected.size + difference.deselected.size
        if (changeCount > descriptions.size) descriptions += "其余 ${changeCount - descriptions.size} 项变更"
        return descriptions.joinToString("\n")
    }

    fun describeSourceTextChange(before: String, after: String): String {
        val beforeLines = before.split('\n')
        val afterLines = after.split('\n')
        val descriptions = mutableListOf<String>()
        var changedCount = 0
        for (index in 0 until maxOf(beforeLines.size, afterLines.size)) {
            if (beforeLines.getOrNull(index) == afterLines.getOrNull(index)) continue
            changedCount++
            if (descriptions.size < MAX_DESCRIPTION_LINES) {
                descriptions += "修改${index + 1}行 修改前${shortText(beforeLines.getOrNull(index).orEmpty().removeSuffix("\r"))}"
            }
        }
        if (changedCount > descriptions.size) {
            descriptions += "其余 ${changedCount - descriptions.size} 行变更"
        }
        return descriptions.joinToString("\n")
    }

    private fun formatHistoryTime(entry: SubtitleEntry): String =
        "${TimeUtils.formatForInput(entry.startTime)}-${TimeUtils.formatForInput(entry.endTime)}"

    private const val MAX_DESCRIPTION_LINES = 100
    private const val MAX_DESCRIPTION_TEXT_LENGTH = 160

    private fun shortText(text: String): String =
        if (text.length <= MAX_DESCRIPTION_TEXT_LENGTH) text
        else text.take(MAX_DESCRIPTION_TEXT_LENGTH) + "…"
}
